package org.ssssssss.magicapi.core.service;

import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.model.MagicEntity;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** 上传整批资源的只读候选，在同一次工作区写锁内交给各资源类型校验。 */
public final class MagicResourceImportContext {
    private final boolean replaceWorkspace;
    private final List<Group> groups;
    private final List<MagicEntity> entities;

    public MagicResourceImportContext(boolean replaceWorkspace, Collection<Group> groups,
                                      Collection<MagicEntity> entities) {
        this.replaceWorkspace = replaceWorkspace;
        this.groups = Collections.unmodifiableList(groups.stream().map(Group::copy).collect(Collectors.toList()));
        this.entities = Collections.unmodifiableList(entities.stream().map(MagicEntity::copy).collect(Collectors.toList()));
    }

    public boolean isReplaceWorkspace() { return replaceWorkspace; }
    public List<Group> getGroups() { return groups; }
    public List<MagicEntity> getEntities() { return entities; }
}
