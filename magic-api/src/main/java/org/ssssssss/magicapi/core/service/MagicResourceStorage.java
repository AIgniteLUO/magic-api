package org.ssssssss.magicapi.core.service;

import org.apache.commons.lang3.StringUtils;
import org.ssssssss.magicapi.core.config.JsonCodeConstants;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.resource.Resource;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.utils.IoUtils;
import org.ssssssss.magicapi.utils.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

public interface MagicResourceStorage<T extends MagicEntity> {

	String separatorWithCRLF = "\r\n================================\r\n";

	String separatorWithLF = "\n================================\n";

	/**
	 * 文件夹名
	 */
	String folder();

	/**
	 * 允许的后缀，为空则不限制
	 */
	String suffix();

	Class<T> magicClass();

	/**
	 * 是否支持path
	 */
	boolean requirePath();

	default boolean requiredScript() {
		return true;
	}

	default boolean allowRoot() {
		return false;
	}

	/** 自定义类型须确认复制与持久化不会丢字段后显式启用。 */
	default boolean supportsRename() {
		return false;
	}

	/** 校验分组和文件名称；默认保留原生命名规则，自定义类型负责保证名称是安全的单路径段。 */
	default void validateName(String name) {
		if (StringUtils.isBlank(name)) throw new InvalidArgumentException(JsonCodeConstants.NAME_REQUIRED);
		if (!IoUtils.validateFileName(name)) throw new InvalidArgumentException(JsonCodeConstants.NAME_INVALID);
	}

	/** 用户发起的全部资源变更在落盘前调用；刷新和集群通知不经过此入口。 */
	default void authorizeWrite() {
	}

	/** 是否参与文件/分组事件触发的逐对象备份；不影响全量导出和备份。 */
	default boolean supportsEventBackup() {
		return true;
	}

	/** 特殊资源可限制编辑器详情、搜索、导出和备份读取；普通资源保持原授权。 */
	default boolean allowRead() {
		return true;
	}

	/** 上传与复制须先校验完整候选集，避免新增类型在批量写入途中才拒绝。 */
	default void validateImport(Collection<T> entities, boolean replaceWorkspace) {
	}

	/** 特殊资源在本地加载和备份解析前检查布局；不能将旧格式当作空工作区。 */
	default void validateResourceRoot(Resource root) {
	}

	/** 全部类型加载后、发布 LOAD 事件前校验跨资源引用。 */
	default void validateLoaded(MagicResourceImportContext context) {
	}

	/** 完整资源装载开始；仅用于读者就绪状态，不应用于保存或备份解析。 */
	default void resourceLoadStarted() {
	}

	/** 全部校验和运行映射事件发布完成后通知，空资源集同样通知。 */
	default void resourceLoadCompleted() {
	}

	/** 装载或通知刷新失败；普通资源保持原行为，有状态读者应显性报告失败。 */
	default void resourceLoadFailed() {
	}

	/** 分组同样属于特殊资源的事务候选；普通分组保持原规则。 */
	default void validateGroup(Group group) {
	}

	/**
	 * 上传落盘前校验跨类型引用并建立仅对本批候选有效的写入范围。
	 * 调用方始终持有工作区写锁，并在任何退出路径关闭范围；普通资源无需实现。
	 */
	default MagicResourceImportScope openImport(MagicResourceImportContext context) {
		return MagicResourceImportScope.NONE;
	}

	/** 引用型资源先写内容和不可变版本，最后写项目头；普通资源保留原有顺序。 */
	default int importOrder() {
		return 0;
	}

	default T read(byte[] bytes) {
		String content = new String(bytes, StandardCharsets.UTF_8);
		if (requiredScript()) {
			String separator = separatorWithCRLF;
			int index = content.indexOf(separator);
			if (index == -1) {
				separator = separatorWithLF;
				index = content.indexOf(separatorWithLF);
			}
			if (index > -1) {
				T info = JsonUtils.readValue(content.substring(0, index), magicClass());
				info.setScript(content.substring(index + separator.length()));
				return info;
			}
		}
		return JsonUtils.readValue(content, magicClass());
	}

	/** 逐对象备份可能使用不同于物理文件的格式，由实际存储负责解码。 */
	default T readBackup(byte[] content) {
		return read(content);
	}

	/** 受管资源可在恢复时复用自己的并发控制及保存流程；普通资源保持原保存行为。 */
	default Object restoreBackup(T entity, MagicResourceService resources, Map<String, String> options) {
		return resources.saveFile(entity);
	}

	/** 分组恢复也由对应存储处理，避免绕过受管资源的完整变更事务。 */
	default Object restoreGroupBackup(Group group, MagicResourceService resources, Map<String, String> options) {
		return resources.saveGroup(group);
	}

	default byte[] write(MagicEntity entity) {
		entity = entity.copy();
		String script = entity.getScript();
		entity.setScript(null);
		return (JsonUtils.toJsonString(entity) + separatorWithCRLF + script).getBytes(StandardCharsets.UTF_8);

	}

	default T readResource(Resource resource) {
		return read(resource.read());
	}

	/** 新类型可要求原子持久化；其余资源继续使用原有存储写入语义。 */
	default boolean writeResource(Resource resource, MagicEntity entity) {
		return resource.write(write(entity));
	}

	String buildMappingKey(T entity);

	default String buildKey(MagicEntity entity) {
		return buildMappingKey((T) entity);
	}

	/**
	 * 校验参数
	 */
	default void validate(T entity) {

	}

	void setMagicResourceService(MagicResourceService magicResourceService);
}
