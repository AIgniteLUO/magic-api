package org.ssssssss.magicapi.core.service;

/** 受控批量导入的校验与写入权限只在当前调用内有效，失败也必须释放。 */
@FunctionalInterface
public interface MagicResourceImportScope extends AutoCloseable {
    MagicResourceImportScope NONE = () -> { };

    @Override
    void close();
}
