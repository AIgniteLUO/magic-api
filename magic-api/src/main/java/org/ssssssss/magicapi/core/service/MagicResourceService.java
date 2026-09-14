package org.ssssssss.magicapi.core.service;

import org.ssssssss.magicapi.core.resource.Resource;
import org.ssssssss.magicapi.core.model.*;
import org.ssssssss.magicapi.core.config.JsonCodeConstants;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.utils.PathUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 资源存储服务
 */
public interface MagicResourceService {

	/** 返回资源服务实际使用的协调器，调用方不得为快照读取另建锁。 */
	default WorkspaceResourceCoordinator getWorkspaceCoordinator() {
		return getResource().getWorkspaceCoordinator();
	}

	/** 整批资源共用一次缓存快照和失败冻结边界，不能只在外层取得协调器写锁。 */
	default <T> T writeTransaction(Supplier<T> action) {
		throw new UnsupportedOperationException("此资源服务不支持完整资源事务");
	}

	/** type=full 用于包含全部类型的导出/备份；自定义服务保持兼容。 */
	default boolean allowRead(String type) {
		return true;
	}

	/** 查询实际注册类型的逐对象事件备份能力；未知类型必须显性拒绝。 */
	boolean supportsEventBackup(String type);

	/**
	 * 刷新缓存
	 */
	void refresh();

	/** 仅重新读取本地资源与运行缓存，不触发外部同步。 */
	default void refreshLocal() {
		throw new UnsupportedOperationException("此资源服务不支持独立本地刷新");
	}

	/** 鉴权回调与持久化使用写锁内取得的同一版本实体。 */
	default ResourceName rename(String id, String name, Predicate<Group> groupAuthorization,
			Predicate<MagicEntity> fileAuthorization) {
		throw new InvalidArgumentException(JsonCodeConstants.RENAME_NOT_SUPPORTED);
	}

	Resource getResource();

	boolean processNotify(MagicNotify magicNotify);

	/**
	 * 保存分组
	 */
	boolean saveGroup(Group group);

	/**
	 * 移动
	 *
	 * @param src     源ID
	 * @param groupId 目标分组
	 */
	boolean move(String src, String groupId);

	/**
	 * 复制分组
	 *
	 * @param src    源ID
	 * @param target 目标分组
	 */
	String copyGroup(String src, String target);

	/**
	 * 全部分组
	 */
	TreeNode<Group> tree(String type);

	/**
	 * 全部分组
	 */
	Map<String, TreeNode<Group>> tree();

	List<Group> getGroupsByFileId(String id);

	/**
	 * 获取分组 Resource
	 *
	 * @param id 分组ID
	 */
	Resource getGroupResource(String id);

	/**
	 * 保存文件
	 *
	 * @param entity 文件内容
	 */
	<T extends MagicEntity> boolean saveFile(T entity);

	/**
	 * 删除文件或文件夹
	 *
	 * @param id 文件或分组ID
	 */
	boolean delete(String id);

	/**
	 * 获取目录下的所有文件
	 *
	 * @param groupId 分组ID
	 */
	<T extends MagicEntity> List<T> listFiles(String groupId);

	/**
	 * 获取所有文件
	 *
	 * @param type 类型
	 */
	<T extends MagicEntity> List<T> files(String type);

	/**
	 * 获取文件详情
	 */
	<T extends MagicEntity> T file(String id);

	/**
	 * 获取分组详情
	 */
	Group getGroup(String id);

	void export(String groupId, List<SelectedResource> resources, OutputStream os) throws IOException;

	/**
	 * 锁定资源
	 */
	boolean lock(String id);

	/**
	 * 解锁资源
	 */
	boolean unlock(String id);

	boolean upload(InputStream inputStream, boolean full) throws IOException;

	boolean upload(InputStream inputStream, boolean full, Set<String> selectedPaths) throws IOException;

	List<Map<String, Object>> previewUpload(InputStream inputStream) throws IOException;

	/**
	 * 获取完整分组路径
	 */
	String getGroupPath(String groupId);

	/**
	 * 获取完整分组名称
	 */
	String getGroupName(String groupId);

	default String getScriptName(MagicEntity entity){
		String fullName;
		if(entity instanceof PathMagicEntity){
			PathMagicEntity pme = (PathMagicEntity) entity;
			fullName = String.format("/%s/%s(/%s/%s)", getGroupName(pme.getGroupId()), pme.getName(), getGroupPath(pme.getGroupId()), pme.getPath());
		} else {
			fullName = String.format("/%s/%s", getGroupName(entity.getGroupId()), entity.getName());
		}
		return PathUtils.replaceSlash(fullName);
	}


}
