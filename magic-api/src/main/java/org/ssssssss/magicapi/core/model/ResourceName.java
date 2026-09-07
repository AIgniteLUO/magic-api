package org.ssssssss.magicapi.core.model;

/** 仅包含已确认的资源名称，避免窄改名响应覆盖编辑器草稿。 */
public final class ResourceName {
	private final String id;
	private final String name;

	public ResourceName(String id, String name) {
		this.id = id;
		this.name = name;
	}

	public String getId() {
		return id;
	}

	public String getName() {
		return name;
	}
}
