package org.ssssssss.magicapi.core.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.ssssssss.magicapi.core.config.Constants;
import org.ssssssss.magicapi.core.config.JsonCodeConstants;
import org.ssssssss.magicapi.core.resource.Resource;
import org.ssssssss.magicapi.core.resource.ZipResource;
import org.ssssssss.magicapi.core.event.EventAction;
import org.ssssssss.magicapi.core.event.FileEvent;
import org.ssssssss.magicapi.core.event.GroupEvent;
import org.ssssssss.magicapi.core.event.MagicEvent;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.model.*;
import org.ssssssss.magicapi.core.service.AbstractPathMagicResourceStorage;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.core.service.MagicResourceStorage;
import org.ssssssss.magicapi.core.service.WorkspaceResourceCoordinator;
import org.ssssssss.magicapi.utils.IoUtils;
import org.ssssssss.magicapi.utils.JsonUtils;
import org.ssssssss.magicapi.utils.WebUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DefaultMagicResourceService implements MagicResourceService, JsonCodeConstants, ApplicationListener<ApplicationStartedEvent> {

	private final Resource root;

	private final Map<String, Resource> groupMappings = new HashMap<>(16);

	private final Map<String, Group> groupCache = new HashMap<>(16);

	private final Map<String, Resource> fileMappings = new HashMap<>(32);

	private final Map<String, MagicEntity> fileCache = new HashMap<>(32);

	private final Map<String, Map<String, String>> pathCache = new HashMap<>(16);

	private final Map<String, MagicResourceStorage<? extends MagicEntity>> storages;

	private final WorkspaceResourceCoordinator coordinator;

	// 由协调写锁保护；整笔移动/上传只保留一次缓存快照。
	private boolean resourceWriteInProgress;

	private final ApplicationEventPublisher publisher;

	private final Logger logger = LoggerFactory.getLogger(DefaultMagicResourceService.class);

	public DefaultMagicResourceService(Resource resource, List<MagicResourceStorage<? extends MagicEntity>> storages, ApplicationEventPublisher publisher) {
		this.root = resource;
		this.storages = storages.stream()
				.peek(it -> it.setMagicResourceService(this))
				.collect(Collectors.toMap(MagicResourceStorage::folder, it -> it));
		this.publisher = publisher;
		WorkspaceResourceCoordinator workspaceCoordinator = resource.getWorkspaceCoordinator();
		this.coordinator = workspaceCoordinator == null ? new WorkspaceResourceCoordinator() : workspaceCoordinator;
		this.coordinator.setLocalRefresh(this::refreshLocal);
		this.coordinator.setResourcePaths(() -> {
			List<String> paths = fileMappings.values().stream().map(Resource::getFilePath).collect(Collectors.toList());
			groupMappings.forEach((id, folder) -> {
				if (!id.endsWith(":0")) {
					paths.add(folder.getResource(Constants.GROUP_METABASE).getFilePath());
				}
			});
			return paths;
		});
	}

	public boolean processNotify(MagicNotify notify) {
		// 未找到通知目标是正常的不同步/重复通知，不代表已经落盘的操作失败。
		boolean applied = refreshCache(() -> processNotifyLocked(notify)
				? NotificationResult.APPLIED : NotificationResult.IGNORED) == NotificationResult.APPLIED;
		if (!applied) {
			logger.warn("忽略本地不存在的资源通知：from={}, type={}, action={}, id={}",
					notify.getFrom(), notify.getType(), notify.getAction(), notify.getId());
		}
		return applied;
	}

	private enum NotificationResult { APPLIED, IGNORED }

	private boolean processNotifyLocked(MagicNotify notify) {
		if (Constants.EVENT_TYPE_FILE.equals(notify.getType())) {
			return processFileNotify(notify.getId(), notify.getAction());
		}
		if (notify.getAction() == EventAction.CLEAR) {
			this.read(false, Constants.EVENT_SOURCE_NOTIFY);
			return true;
		}
		return processGroupNotify(notify.getId(), notify.getAction());
	}

	private boolean processGroupNotify(String id, EventAction action) {
		Group group = groupCache.get(id);
		if (action != EventAction.DELETE) {
			// 分组通知可能晚于子项移动/删除；完整重载同时更新缓存与运行映射。
			this.read(true, Constants.EVENT_SOURCE_NOTIFY);
			return group != null || groupCache.containsKey(id);
		}
		if (group == null) return false;
		GroupEvent event = new GroupEvent(group.getType(), EventAction.DELETE, group, deleteGroup(id));
		event.setSource(Constants.EVENT_SOURCE_NOTIFY);
		publisher.publishEvent(event);
		return true;
	}

	private boolean processFileNotify(String id, EventAction action) {
		MagicEntity entity = fileCache.get(id);
		if (entity == null) {
			if (action == EventAction.DELETE) return false;
			this.read(true, Constants.EVENT_SOURCE_NOTIFY);
			return fileCache.containsKey(id);
		}
		Group group = groupCache.get(entity.getGroupId());
		notNull(group, GROUP_NOT_FOUND);
		MagicResourceStorage<? extends MagicEntity> storage = storages.get(group.getType());
		if (action == EventAction.DELETE) {
			fileMappings.remove(id);
			fileCache.remove(id);
			if (storage.requirePath()) pathCache.get(storage.folder()).remove(id);
		} else {
			Resource resource = fileMappings.get(id);
			resource.readAll();
			if (resource.exists()) {
				entity = storage.read(resource.read());
				putFile(storage, entity, resource);
			} else {
				this.read(true, Constants.EVENT_SOURCE_NOTIFY);
				return true;
			}
		}
		publisher.publishEvent(new FileEvent(group.getType(), action, entity, Constants.EVENT_SOURCE_NOTIFY));
		return true;
	}

	private void init() {
		groupMappings.clear();
		groupCache.clear();
		fileMappings.clear();
		fileCache.clear();
		pathCache.clear();
		storages.forEach((key, registry) -> {
			if (registry.requirePath()) {
				pathCache.put(registry.folder(), new HashMap<>(32));
			}
			Resource folder = root.getDirectory(key);
			if (registry.allowRoot()) {
				String rootId = key + ":0";
				Group group = new Group();
				group.setId(rootId);
				group.setType(key);
				group.setParentId("0");
				putGroup(group, folder);
			}
			if (!folder.exists()) {
				folder.mkdir();
			}
		});
	}

	private void read(boolean triggerEvent) {
		read(triggerEvent, null);
	}

	private void read(boolean triggerEvent, String source) {
		refreshCache(() -> {
			this.readAll();
			if (triggerEvent) {
				publisher.publishEvent(new MagicEvent("clear", EventAction.CLEAR, source));
			}
			fileCache.values().forEach(entity -> {
				Group group = groupCache.get(entity.getGroupId());
				publisher.publishEvent(new FileEvent(group.getType(), EventAction.LOAD, entity, source));
			});
			return null;
		});
	}

	private <R> R refreshCache(Supplier<R> action) {
		return writeLock(() -> {
			try {
				return action.get();
			} catch (RuntimeException | Error failure) {
				// 外部存储刷新没有本进程的磁盘写入记录，也不能发布半次加载。
				if (root.getWorkspaceCoordinator() != null) {
					coordinator.markInconsistent();
				}
				throw failure;
			}
		});
	}

	private void readAll() {
		writeLock(() -> {
			this.init();
			this.root.readAll();
			storages.forEach((key, registry) -> refreshGroup(root.getDirectory(key), registry));
			return null;
		});
	}

	@Override
	public void refresh() {
		coordinator.assertCanWait();
		root.reload(this::refreshLocal);
	}

	@Override
	public void refreshLocal() {
		this.read(true);
	}

	@Override
	public Resource getResource() {
		return root;
	}

	@Override
	public ResourceName rename(String id, String name, Predicate<Group> groupAuthorization,
			Predicate<MagicEntity> fileAuthorization) {
		isTrue(!root.readonly(), IS_READ_ONLY);
		return writeLock(() -> {
			Group currentGroup = groupCache.get(id);
			if (currentGroup != null) {
				isTrue(!id.endsWith(":0"), RENAME_NOT_SUPPORTED);
				Group renamed = currentGroup.copy();
				renamed.setName(name);
				isTrue(groupAuthorization.test(renamed), PERMISSION_INVALID);
				isTrue(saveGroup(renamed), FILE_SAVE_FAILURE);
			} else {
				MagicEntity current = fileCache.get(id);
				notNull(current, FILE_NOT_FOUND);
				Group group = groupCache.get(current.getGroupId());
				MagicResourceStorage<?> storage = storages.get(group.getType());
				isTrue(storage.supportsRename(), RENAME_NOT_SUPPORTED);
				MagicEntity renamed = current.copy();
				isTrue(renamed.getClass() == current.getClass(), RENAME_NOT_SUPPORTED);
				renamed.setName(name);
				isTrue(fileAuthorization.test(renamed), PERMISSION_INVALID);
				isTrue(saveFile(renamed), FILE_SAVE_FAILURE);
			}
			return new ResourceName(id, name);
		});
	}

	private void validateName(String name) {
		notBlank(name, NAME_REQUIRED);
		isTrue(IoUtils.validateFileName(name), NAME_INVALID);
	}

	private void validateDestination(Resource current, Resource target) {
		isTrue(current != null && Objects.equals(current.getAbsolutePath(), target.getAbsolutePath())
				|| !target.exists(), NAME_CONFLICT);
	}

	private void refreshGroup(Resource folder, MagicResourceStorage<? extends MagicEntity> storage) {
		if (storage.allowRoot()) {
			folder.files(storage.suffix()).forEach(file -> putFile(storage, storage.readResource(file), file));
			return;
		}
		List<Resource> directories = new ArrayList<>(folder.dirs());
		if (folder.getResource(Constants.GROUP_METABASE).exists()) {
			directories.add(folder);
		}
		List<Resource> groups = new ArrayList<>();
		for (Resource directory : directories) {
			Resource meta = directory.getResource(Constants.GROUP_METABASE);
			if (meta.exists()) {
				Group group = JsonUtils.readValue(meta.read(), Group.class);
				notNull(group, GROUP_NOT_FOUND);
				putGroup(group, directory);
				groups.add(directory);
			}
		}
		// 先加载全部祖先分组，随后计算文件路由；包含被改名的分组本身，避免映射残留旧目录。
		for (Resource directory : groups) {
			directory.files(storage.suffix()).stream()
					.filter(file -> Objects.equals(directory.getAbsolutePath(), file.parent().getAbsolutePath()))
					.forEach(file -> putFile(storage, storage.readResource(file), file));
		}
	}

	@Override
	public boolean saveGroup(Group group) {
		isTrue(!root.readonly(), IS_READ_ONLY);
		// 类型校验
		isTrue(storages.containsKey(group.getType()), NOT_SUPPORTED_GROUP_TYPE);
		// 名字校验
		validateName(group.getName());
		// 需要填写parentId
		notNull(group.getParentId(), GROUP_ID_REQUIRED);
		MagicResourceStorage<? extends MagicEntity> storage = storages.get(group.getType());
		return writeLock(() -> {
			Resource resource;
			// 判断是否要保存到根节点下
			if (Constants.ROOT_ID.equals(group.getParentId())) {
				resource = root.getDirectory(group.getType());
			} else {
				// 找到上级分组
				resource = getGroupResource(group.getParentId());
				// 上级分组需要存在
				isTrue(resource != null && resource.exists(), GROUP_NOT_FOUND);
			}
			Resource groupResource;
			GroupEvent event = new GroupEvent(group.getType(), group.getId() == null ? EventAction.CREATE : EventAction.SAVE, group);
			if (group.getId() == null || !groupCache.containsKey(group.getId())) {
				// 添加分组
				if (group.getId() == null) {
					group.setId(UUID.randomUUID().toString().replace("-", ""));
				}
				group.setCreateTime(System.currentTimeMillis());
				group.setCreateBy(WebUtils.currentUserName());
				groupResource = resource.getDirectory(group.getName());
				// 判断分组文件夹需要不存在
				validateDestination(null, groupResource);
				// 创建文件夹
				isTrue(groupResource.mkdir(), FILE_SAVE_FAILURE);
			} else {
				Group oldGroup = groupCache.get(group.getId());
				group.setCreateBy(oldGroup.getCreateBy());
				group.setCreateTime(oldGroup.getCreateTime());
				boolean parentChanged = !Objects.equals(oldGroup.getParentId(), group.getParentId());
				if (parentChanged) {
					isTrue(tree(group.getType()).findTreeNode(it -> it.getId().equals(group.getId())).flat().stream()
							.noneMatch(it -> it.getId().equals(group.getParentId())), MOVE_PATH_CONFLICT);
				}
				if (storage.requirePath() && (parentChanged || !Objects.equals(oldGroup.getPath(), group.getPath()))) {
					TreeNode<Group> treeNode = tree(group.getType());
					// 递归找出该组下的文件
					List<MagicEntity> entities = treeNode.findTreeNode(it -> it.getId().equals(group.getId()))
							.flat()
							.stream()
							.flatMap(it -> fileCache.values().stream().filter(f -> f.getGroupId().equals(it.getId())))
							.collect(Collectors.toList());
					groupCache.put(group.getId(), group);
					try {
						for (MagicEntity entity : entities) {
							String newMappingKey = storage.buildKey(entity);
							isTrue(pathCache.get(group.getType()).entrySet().stream().noneMatch(entry -> entry.getValue().equals(newMappingKey) && !entry.getKey().equals(entity.getId())), SAVE_GROUP_PATH_CONFLICT);
						}
					} finally {
						groupCache.put(oldGroup.getId(), oldGroup);
					}
				}
				Resource oldResource = getGroupResource(group.getId());
				groupResource = resource.getDirectory(group.getName());
				isTrue(oldResource != null && oldResource.exists(), GROUP_NOT_FOUND);
				// 设置修改时间，修改人
				group.setUpdateBy(WebUtils.currentUserName());
				group.setUpdateTime(System.currentTimeMillis());
				// 父分组变化也会改变物理目标，不能仅按显示名称判断是否移动。
				if (!Objects.equals(oldResource.getAbsolutePath(), groupResource.getAbsolutePath())) {
					// 判断分组文件夹需要不存在
					validateDestination(oldResource, groupResource);
					isTrue(oldResource.renameTo(groupResource), FILE_SAVE_FAILURE);
				}
			}
			// 写入分组信息
			if (groupResource.getResource(Constants.GROUP_METABASE).write(JsonUtils.toJsonString(group))) {
				putGroup(group, groupResource);
				TreeNode<Group> treeNode = tree(group.getType()).findTreeNode(it -> it.getId().equals(group.getId()));
				// 刷新分组缓存
				refreshGroup(groupResource, storage);
				if (event.getAction() != EventAction.CREATE) {
					event.setEntities(treeNode
							.flat()
							.stream()
							.flatMap(g -> listFiles(g.getId()).stream())
							.collect(Collectors.toList()));
				}
				publisher.publishEvent(event);
				return true;
			}
			return false;
		});
	}


	@Override
	public boolean move(String src, String groupId) {
		isTrue(!root.readonly(), IS_READ_ONLY);
		return writeLock(() -> {
			Group group = groupCache.get(groupId);
			isTrue("0".equals(groupId) || group != null, GROUP_NOT_FOUND);
			isTrue(!Objects.equals(src, groupId), MOVE_NAME_CONFLICT);
			Group srcGroup = groupCache.get(src);
			if (srcGroup != null) {
				// 移动分组
				return moveGroup(srcGroup, groupId);
			} else {
				// 不能将文件移动至根节点下
				notNull(group, GROUP_NOT_FOUND);
				MagicEntity entity = fileCache.get(src);
				notNull(entity, FILE_NOT_FOUND);
				// 移动文件
				return moveFile(entity.copy(), group);
			}
		});
	}

	@Override
	public String copyGroup(String src, String groupId) {
		return writeLock(() -> copyGroupLocked(src, groupId));
	}

	private String copyGroupLocked(String src, String groupId) {
		isTrue(!root.readonly(), IS_READ_ONLY);
		Group group = groupCache.get(groupId);
		isTrue("0".equals(groupId) || group != null, GROUP_NOT_FOUND);
		isTrue(!Objects.equals(src, groupId), SRC_GROUP_CONFLICT);
		Group srcGroup = groupCache.get(src);
		notNull(srcGroup, GROUP_NOT_FOUND);
		Group newGroup = new Group();
		newGroup.setType(srcGroup.getType());
		newGroup.setParentId(groupId);
		newGroup.setName(srcGroup.getName() + "(Copy)");
		newGroup.setPath(Objects.toString(srcGroup.getPath(), "") + "_copy");
		newGroup.setOptions(srcGroup.getOptions());
		newGroup.setPaths(srcGroup.getPaths());
		newGroup.setProperties(srcGroup.getProperties());
		isTrue(saveGroup(newGroup), FILE_SAVE_FAILURE);
		listFiles(src).stream()
				.map(MagicEntity::copy)
				.peek(it -> it.setGroupId(newGroup.getId()))
				.peek(it -> it.setId(null))
				.forEach(it -> isTrue(saveFile(it), FILE_SAVE_FAILURE));
		return newGroup.getId();
	}

	/**
	 * 移动分组
	 *
	 * @param src    分组信息
	 * @param target 目标分组ID
	 */
	private boolean moveGroup(Group src, String target) {
		isTrue(!root.readonly(), IS_READ_ONLY);
		MagicResourceStorage<?> storage = storages.get(src.getType());
		Resource parent = Constants.ROOT_ID.equals(target) ? root.getDirectory(storage.folder()) : groupMappings.get(target);
		notNull(parent, GROUP_NOT_FOUND);
		Resource targetResource = parent.getDirectory(src.getName());
		isTrue(!targetResource.exists(), MOVE_NAME_CONFLICT);
		TreeNode<Group> subtree = tree(storage.folder()).findTreeNode(it -> it.getId().equals(src.getId()));
		List<Group> descendants = subtree.flat();
		isTrue(descendants.stream().noneMatch(it -> it.getId().equals(target)), MOVE_PATH_CONFLICT);
		List<MagicEntity> entities = descendants.stream()
				.flatMap(it -> fileCache.values().stream().filter(f -> f.getGroupId().equals(it.getId())))
				.collect(Collectors.toList());
		Group moved = src.copy();
		moved.setParentId(target);
		// 路径校验暂借缓存计算完整祖先路径，任何失败都还原，持久化前不发布新实体。
		groupCache.put(src.getId(), moved);
		try {
			if (storage.requirePath()) {
				for (MagicEntity entity : entities) {
					String key = storage.buildKey(entity);
					isTrue(pathCache.get(src.getType()).entrySet().stream().noneMatch(entry -> entry.getValue().equals(key) && !entry.getKey().equals(entity.getId())), MOVE_PATH_CONFLICT);
				}
			}
		} finally {
			groupCache.put(src.getId(), src);
		}
		moved.setUpdateBy(WebUtils.currentUserName());
		moved.setUpdateTime(System.currentTimeMillis());
		Resource oldResource = groupMappings.get(src.getId());
		if (oldResource.renameTo(targetResource)) {
			if (targetResource.getResource(Constants.GROUP_METABASE).write(JsonUtils.toJsonString(moved))) {
				putGroup(moved, targetResource);
				if (storage.requirePath()) {
					Map<String, String> paths = pathCache.get(storage.folder());
					entities.forEach(entity -> paths.put(entity.getId(), storage.buildKey(entity)));
				}
				refreshGroup(targetResource, storage);
				publisher.publishEvent(new GroupEvent(src.getType(), EventAction.MOVE, moved, entities));
				return true;
			}
		}
		return false;
	}

	/**
	 * 移动文件
	 *
	 * @param entity 文件信息
	 * @param group  目标分组
	 */
	private <T extends MagicEntity> boolean moveFile(T entity, Group group) {
		isTrue(!root.readonly(), IS_READ_ONLY);
		// 判断是否被锁定
		isTrue(!Constants.LOCK.equals(entity.getLock()), RESOURCE_LOCKED);
		// 设置新的分组ID
		entity.setGroupId(group.getId());
		MagicResourceStorage<?> storage = storages.get(group.getType());
		// 计算mappingKey
		String newMappingKey = storage.buildKey(entity);
		Resource resource = groupMappings.get(group.getId());
		// 判断名字和路径是否有冲突
		Resource newResource = resource.getResource(entity.getName() + storage.suffix());
		isTrue(!newResource.exists(), MOVE_NAME_CONFLICT);
		isTrue(!storage.requirePath() || pathCache.get(storage.folder()).entrySet().stream().noneMatch(entry -> entry.getValue().equals(newMappingKey) && !entry.getKey().equals(entity.getId())), MOVE_PATH_CONFLICT);
		// 设置修改时间，修改人
		entity.setUpdateBy(WebUtils.currentUserName());
		entity.setUpdateTime(System.currentTimeMillis());
		// 写入新文件
		if (newResource.write(storage.write(entity))) {
			// 删除旧文件
			isTrue(fileMappings.get(entity.getId()).delete(), FILE_SAVE_FAILURE);
			// 写入缓存
			putFile(storage, entity, newResource);
			publisher.publishEvent(new FileEvent(group.getType(), EventAction.MOVE, entity));
			return true;
		}
		return false;
	}

	@Override
	public TreeNode<Group> tree(String type) {
		return readLock(() -> groupCache.values().stream().filter(it -> type.equals(it.getType())).collect(Collectors.collectingAndThen(Collectors.toList(), this::convertToTree)));
	}

	@Override
	public Map<String, TreeNode<Group>> tree() {
		return readLock(() -> groupCache.values().stream().collect(Collectors.groupingBy(Group::getType, Collectors.collectingAndThen(Collectors.toList(), this::convertToTree))));
	}

	@Override
	public List<Group> getGroupsByFileId(String id) {
		return readLock(() -> {
			MagicEntity entity = fileCache.get(id);
			List<Group> groups = entity == null ? new ArrayList<>() : findGroups(entity.getGroupId());
			Collections.reverse(groups);
			return groups;
		});
	}

	private TreeNode<Group> convertToTree(List<Group> groups) {
		TreeNode<Group> root = new TreeNode<>();
		root.setNode(new Group("0", "root"));
		convertToTree(groups, root);
		return root;
	}

	private void convertToTree(List<Group> remains, TreeNode<Group> current) {
		Group temp;
		List<TreeNode<Group>> childNodes = new LinkedList<>();
		Iterator<Group> iterator = remains.iterator();
		while (iterator.hasNext()) {
			temp = iterator.next();
			if (current.getNode().getId().equals(temp.getParentId())) {
				childNodes.add(new TreeNode<>(temp));
				iterator.remove();
			}
		}
		current.setChildren(childNodes);
		childNodes.forEach(it -> convertToTree(remains, it));
	}


	@Override
	public Resource getGroupResource(String id) {
		return readLock(() -> groupMappings.get(id));
	}

	@Override
	public <T extends MagicEntity> boolean saveFile(T entity) {
		isTrue(!root.readonly(), IS_READ_ONLY);
		// 校验必填信息
		notNull(entity.getGroupId(), GROUP_ID_REQUIRED);
		// 校验名字
		validateName(entity.getName());
		return writeLock(() -> {
			EventAction action = entity.getId() == null || !fileCache.containsKey(entity.getId()) ? EventAction.CREATE : EventAction.SAVE;
			// 获取所在分组
			Resource groupResource = getGroupResource(entity.getGroupId());
			// 分组需要存在
			notNull(groupResource, GROUP_NOT_FOUND);
			MagicResourceStorage<T> storage;
			if (entity.getGroupId().contains(":")) {
				storage = (MagicResourceStorage<T>) this.storages.get(entity.getGroupId().split(":")[0]);
			} else {
				// 读取分组信息
				Group group = groupCache.get(entity.getGroupId());
				storage = (MagicResourceStorage<T>) this.storages.get(group.getType());
			}

			// 检查脚本
			if (storage.requiredScript()) {
				notBlank(entity.getScript(), SCRIPT_REQUIRED);
			}
			// 路径检查
			if (storage.requirePath()) {
				notBlank(((PathMagicEntity) entity).getPath(), PATH_REQUIRED);
				String newMappingKey = storage.buildKey(entity);
				isTrue(pathCache.get(storage.folder()).entrySet().stream().noneMatch(entry -> entry.getValue().equals(newMappingKey) && !entry.getKey().equals(entity.getId())), PATH_CONFLICT);
			}
			storage.validate(entity);
			// 拼接文件名
			String filename = entity.getName() + storage.suffix();
			// 获取修改前的信息
			Resource fileResource = groupResource.getResource(filename);
			validateDestination(action == EventAction.CREATE ? null : fileMappings.get(entity.getId()), fileResource);
			if (action == EventAction.CREATE) {
				if (entity.getId() == null) {
					// 新增操作赋值
					entity.setId(UUID.randomUUID().toString().replace("-", ""));
				}
				entity.setCreateTime(System.currentTimeMillis());
				entity.setCreateBy(WebUtils.currentUserName());
			} else {
				MagicEntity oldEntity = fileCache.get(entity.getId());
				// 防止创建信息被修改
				entity.setCreateBy(oldEntity.getCreateBy());
				entity.setCreateTime(oldEntity.getCreateTime());
				// 修改操作赋值
				entity.setUpdateTime(System.currentTimeMillis());
				entity.setUpdateBy(WebUtils.currentUserName());
				isTrue(!Constants.LOCK.equals(fileCache.get(entity.getId()).getLock()), RESOURCE_LOCKED);
				Resource oldFileResource = fileMappings.get(entity.getId());
				if (!Objects.equals(oldFileResource.getAbsolutePath(), fileResource.getAbsolutePath())) {
					// 重命名
					isTrue(oldFileResource.renameTo(fileResource), FILE_SAVE_FAILURE);
				}
			}
			boolean flag = fileResource.write(storage.write(entity));
			if (flag) {
				publisher.publishEvent(new FileEvent(storage.folder(), action, entity));
				putFile(storage, entity, fileResource);
			}
			return flag;
		});
	}

	private List<MagicEntity> deleteGroup(String id) {
		// 这里只移除本地缓存；用户删除入口负责只读校验，集群通知仍需更新只读实例。
		Group group = groupCache.get(id);
		List<MagicEntity> entities = new ArrayList<>();
		// 递归删除分组和文件
		tree(group.getType())
				.findTreeNode(it -> it.getId().equals(id))
				.flat()
				.forEach(g -> {
					groupCache.remove(g.getId());
					groupMappings.remove(g.getId());
					fileCache.values().stream()
							.filter(f -> f.getGroupId().equals(g.getId())).peek(entities::add)
							.collect(Collectors.toList())
							.forEach(file -> {
								fileCache.remove(file.getId());
								fileMappings.remove(file.getId());
								Map<String, String> map = pathCache.get(g.getType());
								if (map != null) {
									map.remove(file.getId());
								}
							});
				});
		groupMappings.remove(id);
		groupCache.remove(id);
		return entities;
	}

	@Override
	public boolean delete(String id) {
		isTrue(!root.readonly(), IS_READ_ONLY);
		return writeLock(() -> {
			Resource resource = getGroupResource(id);
			if (resource != null) {
				// 删除分组
				if (resource.exists() && resource.delete()) {
					Group group = groupCache.get(id);
					GroupEvent event = new GroupEvent(groupCache.get(group.getId()).getType(), EventAction.DELETE, group);
					event.setEntities(deleteGroup(id));
					publisher.publishEvent(event);
					return true;
				}
			}
			resource = fileMappings.get(id);
			// 删除文件
			if (resource != null && resource.exists() && resource.delete()) {
				MagicEntity entity = fileCache.remove(id);
				String type = groupCache.get(entity.getGroupId()).getType();
				publisher.publishEvent(new FileEvent(type, EventAction.DELETE, entity));
				fileMappings.remove(id);
				fileCache.remove(id);
				Map<String, String> map = pathCache.get(type);
				if (map != null) {
					map.remove(id);
				}

			}
			return true;
		});
	}

	@Override
	public <T extends MagicEntity> List<T> listFiles(String groupId) {
		return readLock(() -> {
			Group group = groupCache.get(groupId);
			notNull(group, GROUP_NOT_FOUND);
			return fileCache.values().stream()
					.filter(it -> it.getGroupId().equals(groupId))
					.map(it -> (T) it)
					.collect(Collectors.toList());
		});
	}

	@Override
	public <T extends MagicEntity> List<T> files(String type) {
		return readLock(() -> filesLocked(type));
	}

	private <T extends MagicEntity> List<T> filesLocked(String type) {
		MagicResourceStorage<? extends MagicEntity> storage = storages.get(type);
		Resource directory = root.getDirectory(type);
		if (directory.exists()) {
			return directory.files(storage.suffix()).stream()
					.map(storage::readResource)
					.map(it -> (T) it)
					.collect(Collectors.toList());
		}
		return Collections.emptyList();
	}

	@Override
	public <T extends MagicEntity> T file(String id) {
		return readLock(() -> (T) fileCache.get(id));
	}

	@Override
	public Group getGroup(String id) {
		return readLock(() -> groupCache.get(id));
	}

	@Override
	public void export(String groupId, List<SelectedResource> resources, OutputStream os) throws IOException {
		try {
			readLock(() -> {
				try {
					exportLocked(groupId, resources, os);
				} catch (IOException failure) {
					throw new UncheckedIOException(failure);
				}
				return null;
			});
		} catch (UncheckedIOException failure) {
			throw failure.getCause();
		}
	}

	private void exportLocked(String groupId, List<SelectedResource> resources, OutputStream os) throws IOException {
		if (StringUtils.isNotBlank(groupId)) {
			Resource resource = getGroupResource(groupId);
			notNull(resource, GROUP_NOT_FOUND);
			resource.export(os);
		} else if (resources == null || resources.isEmpty()) {
			root.export(os);
		} else {
			ZipOutputStream zos = new ZipOutputStream(os);
			for (SelectedResource item : resources) {
				if ("root".equals(item.getType())) {
					zos.putNextEntry(new ZipEntry(item.getId() + "/"));
				} else if ("group".equals(item.getType())) {
					Resource resource = getGroupResource(item.getId());
					notNull(resource, GROUP_NOT_FOUND);
					zos.putNextEntry(new ZipEntry(resource.getFilePath()));
					zos.closeEntry();
					resource = resource.getResource(Constants.GROUP_METABASE);
					zos.putNextEntry(new ZipEntry(resource.getFilePath()));
					zos.write(resource.read());
					zos.closeEntry();
				} else {
					Resource resource = fileMappings.get(item.getId());
					MagicEntity entity = fileCache.get(item.getId());
					notNull(entity, FILE_NOT_FOUND);
					Resource groupResource = groupMappings.get(entity.getGroupId());
					Group group = groupCache.get(entity.getGroupId());
					MagicResourceStorage<? extends MagicEntity> storage = storages.get(group.getType());
					zos.putNextEntry(new ZipEntry(groupResource.getFilePath() + entity.getName() + storage.suffix()));
					zos.write(resource.read());
					zos.closeEntry();
				}
			}
			zos.flush();
			zos.close();
		}
	}

	@Override
	public boolean lock(String id) {
		return doLockResource(id, Constants.LOCK);
	}

	private boolean doLockResource(String id, String lockState) {
		isTrue(!root.readonly(), IS_READ_ONLY);
		return writeLock(() -> {
			MagicEntity entity = fileCache.get(id);
			Resource resource = fileMappings.get(id);
			notNull(entity, FILE_NOT_FOUND);
			notNull(resource, FILE_NOT_FOUND);
			Group group = groupCache.get(entity.getGroupId());
			notNull(group, GROUP_NOT_FOUND);
			entity = entity.copy();
			entity.setLock(lockState);
			entity.setUpdateTime(System.currentTimeMillis());
			entity.setUpdateBy(WebUtils.currentUserName());
			MagicResourceStorage<? extends MagicEntity> storage = storages.get(group.getType());
			boolean flag = resource.write(storage.write(entity));
			if (flag) {
				putFile(storage, entity, resource);
			}
			return flag;
		});
	}

	@Override
	public boolean unlock(String id) {
		return doLockResource(id, Constants.UNLOCK);
	}

	@Override
	public boolean upload(InputStream inputStream, boolean full) throws IOException {
		return upload(inputStream, full, null);
	}

	@Override
	public List<Map<String, Object>> previewUpload(InputStream inputStream) throws IOException {
		ZipResource zipResource = new ZipResource(inputStream);
		try {
			List<Map<String, Object>> resources = new ArrayList<>();
			collectUploadPaths(zipResource, new ArrayList<>(), resources, null);
			return resources;
		} finally {
			zipResource.close();
			IoUtils.close(inputStream);
		}
	}

	private void collectUploadPaths(Resource resource, List<Map<String, String>> groups, List<Map<String, Object>> resources, MagicResourceStorage<? extends MagicEntity> inheritedStorage) {
		MagicResourceStorage<? extends MagicEntity> storage = inheritedStorage == null ? storages.get(resource.name()) : inheritedStorage;
		List<Map<String, String>> currentGroups = groups;
		Resource groupMeta = resource.getResource(Constants.GROUP_METABASE);
		if (groupMeta.exists()) {
			Group group = JsonUtils.readValue(groupMeta.read(), Group.class);
			currentGroups = new ArrayList<>(groups);
			Map<String, String> groupInfo = new LinkedHashMap<>();
			groupInfo.put("name", group.getName());
			groupInfo.put("path", group.getPath());
			currentGroups.add(groupInfo);
		}
		for (Resource file : resource.resources()) {
			if (file.isDirectory()) {
				collectUploadPaths(file, currentGroups, resources, storage);
			} else if (file.name().endsWith(".json") || file.name().endsWith(".ms")) {
				if (Constants.GROUP_METABASE.equals(file.name())) continue;
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("rawPath", file.getFilePath());
				item.put("name", file.name().replaceFirst("\\.(ms|json)$", ""));
				item.put("groups", currentGroups);
				if (storage != null && file.name().endsWith(storage.suffix())) {
					MagicEntity entity = storage.read(file.read());
					item.put("name", entity.getName());
					item.put("type", storage.folder());
					item.put("root", storage.folder());
					item.put("textIcon", true);
					if (entity instanceof PathMagicEntity) item.put("path", ((PathMagicEntity) entity).getPath());
					if (entity instanceof ApiInfo) item.put("method", ((ApiInfo) entity).getMethod());
				}
				resources.add(item);
			}
		}
	}

	@Override
	public boolean upload(InputStream inputStream, boolean full, Set<String> selectedPaths) throws IOException {
		isTrue(!root.readonly(), IS_READ_ONLY);
		try {
			ZipResource zipResource = new ZipResource(inputStream);
			Set<Group> groups = new LinkedHashSet<>();
			Set<MagicEntity> entities = new LinkedHashSet<>();
			Map<String, Map<String, String>> existingNames = new HashMap<>();
			try {
			return writeLock(() -> {
				fileCache.values().forEach(entity -> existingNames.computeIfAbsent(entity.getGroupId(), key -> new HashMap<>()).put(entity.getName(), entity.getId()));
				readAllResource(zipResource, groups, entities, !full, existingNames, selectedPaths);
				if (full) {
					// 全量模式先删除处理。
					isTrue(root.delete(), FILE_SAVE_FAILURE);
					this.init();
					publisher.publishEvent(new MagicEvent("clear", EventAction.CLEAR));
				}
				for (Group group : groups) {
					isTrue(saveGroup(group), FILE_SAVE_FAILURE);
				}
				for (MagicEntity entity : entities) {
					isTrue(saveFile(entity), FILE_SAVE_FAILURE);
				}
				return true;
			});
			} finally {
				zipResource.close();
			}
		} finally {
			IoUtils.close(inputStream);
		}

	}

	private void readAllResource(Resource root, Set<Group> groups, Set<MagicEntity> entities, boolean checked, Map<String, Map<String, String>> existingNames, Set<String> selectedPaths) {
		Resource resource = root.getResource(Constants.GROUP_METABASE);
		MagicResourceStorage<? extends MagicEntity> storage = null;
		if (resource.exists()) {
			Group group = JsonUtils.readValue(resource.read(), Group.class);
			group.setType(mappingV1Type(group.getType()));
			storage = storages.get(group.getType());
			notNull(storage, NOT_SUPPORTED_GROUP_TYPE);
		}
		readAllResource(root, storage, groups, entities, null, "/", checked, existingNames, selectedPaths);
	}

	private void readAllResource(Resource root, MagicResourceStorage<? extends MagicEntity> storage, Set<Group> groups, Set<MagicEntity> entities, Set<String> mappingKeys, String path, boolean checked, Map<String, Map<String, String>> existingNames, Set<String> selectedPaths) {
		storage = storage == null ? storages.get(root.name()) : storage;
		if (storage != null) {
			mappingKeys = mappingKeys == null ? new HashSet<>() : mappingKeys;
			if (!storage.allowRoot()) {
				Resource resource = root.getResource(Constants.GROUP_METABASE);
				// 分组信息不存在时，直接返回不处理
				if (resource.exists()) {
					// 读取分组信息
					Group group = JsonUtils.readValue(resource.read(), Group.class);
					group.setType(mappingV1Type(group.getType()));
					groups.add(group);
					if (storage.requirePath()) {
						path = path + Objects.toString(group.getPath(), "") + "/";
					}
				}

			}
			for (Resource file : root.files(storage.suffix())) {
				if (selectedPaths != null && !selectedPaths.contains(file.getFilePath())) continue;
				MagicEntity entity = storage.read(file.read());
				if (storage.allowRoot()) {
					entity.setGroupId(storage.folder() + ":0");
				}
				String mappingKey;
				if (storage instanceof AbstractPathMagicResourceStorage) {
					mappingKey = ((AbstractPathMagicResourceStorage) storage).buildMappingKey((PathMagicEntity) entity, path);
				} else {
					mappingKey = storage.buildKey(entity);
				}
				if (checked) {
					String groupId = entity.getGroupId();
					// 名字和路径冲突检查
					String existingId = existingNames.getOrDefault(groupId, Collections.emptyMap()).get(entity.getName());
					isTrue(existingId == null || Objects.equals(existingId, entity.getId()), RESOURCE_PATH_CONFLICT.format(entity.getName()));

				}
				// 自检
				isTrue(mappingKeys.add(mappingKey), RESOURCE_PATH_CONFLICT.format(mappingKey));
				entities.add(entity);
			}

		}
		for (Resource directory : root.dirs()) {
			readAllResource(directory, storage, groups, entities, mappingKeys, path, checked, existingNames, selectedPaths);
		}
	}

	/**
	 * 兼容1.x版本
	 */
	private String mappingV1Type(String type) {
		if ("1".equals(type)) {
			return "api";
		} else if ("2".equals(type)) {
			return "function";
		}
		return type;

	}

	@Override
	public String getGroupName(String groupId) {
		return findGroups(groupId).stream()
				.map(Group::getName)
				.collect(Collectors.joining("/"));
	}

	@Override
	public String getGroupPath(String groupId) {
		return findGroups(groupId).stream()
				.map(Group::getPath)
				.filter(StringUtils::isNotBlank)
				.collect(Collectors.joining("/"));
	}

	private List<Group> findGroups(String groupId) {
		return readLock(() -> {
			List<Group> groups = new ArrayList<>();
			Set<String> visited = new HashSet<>();
			String key = groupId;
			while (groupCache.containsKey(key)) {
				// 外部元数据也可能包含父级循环，不能让加载一直占住资源锁。
				isTrue(visited.add(key), GROUP_NOT_FOUND);
				Group group = groupCache.get(key);
				groups.add(0, group);
				key = group.getParentId();
			}
			return groups;
		});
	}

	private void putGroup(Group group, Resource resource) {
		notNull(group, GROUP_NOT_FOUND);
		notBlank(group.getId(), GROUP_NOT_FOUND);
		notNull(group.getParentId(), GROUP_ID_REQUIRED);
		groupMappings.put(group.getId(), resource);
		groupCache.put(group.getId(), group);
	}

	private void putFile(MagicResourceStorage<?> storage, MagicEntity entity, Resource resource) {
		notNull(entity, FILE_NOT_FOUND);
		notBlank(entity.getId(), FILE_NOT_FOUND);
		notNull(groupCache.get(entity.getGroupId()), GROUP_NOT_FOUND);
		fileMappings.put(entity.getId(), resource);
		fileCache.put(entity.getId(), entity);
		if (storage.requirePath()) {
			pathCache.get(storage.folder()).put(entity.getId(), storage.buildKey(entity));
		}
	}

	private <R> R readLock(Supplier<R> supplier) {
		return coordinator.read(supplier);
	}

	private <R> R writeLock(Supplier<R> supplier) {
		return coordinator.write(() -> {
			if (resourceWriteInProgress) {
				return supplier.get();
			}
			CacheSnapshot previous = new CacheSnapshot();
			resourceWriteInProgress = true;
			try {
				// 内层先识别部分写入后的 false/异常，再在外层释放锁前还原缓存。
				return coordinator.write(supplier);
			} catch (RuntimeException | Error failure) {
				previous.restore();
				throw failure;
			} finally {
				resourceWriteInProgress = false;
			}
		});
	}

	private final class CacheSnapshot {
		private final Map<String, Resource> groups = new HashMap<>(groupMappings);
		private final Map<String, Group> groupDetails = new HashMap<>(groupCache);
		private final Map<String, Resource> files = new HashMap<>(fileMappings);
		private final Map<String, MagicEntity> fileDetails = new HashMap<>(fileCache);
		private final Map<String, Map<String, String>> paths = pathCache.entrySet().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, entry -> new HashMap<>(entry.getValue())));

		private void restore() {
			groupMappings.clear(); groupMappings.putAll(groups);
			groupCache.clear(); groupCache.putAll(groupDetails);
			fileMappings.clear(); fileMappings.putAll(files);
			fileCache.clear(); fileCache.putAll(fileDetails);
			pathCache.clear(); pathCache.putAll(paths);
		}
	}

	@Override
	public void onApplicationEvent(ApplicationStartedEvent applicationStartedEvent) {
		try {
			this.read(false);
		} catch (Exception e) {
			logger.error("启动过程中发生异常", e);
		}
	}
}
