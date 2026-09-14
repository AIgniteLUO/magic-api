package org.ssssssss.magicapi.backup.web;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.ssssssss.magicapi.backup.model.Backup;
import org.ssssssss.magicapi.core.config.Constants;
import org.ssssssss.magicapi.core.web.MagicController;
import org.ssssssss.magicapi.core.web.MagicExceptionHandler;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.model.*;
import org.ssssssss.magicapi.backup.service.MagicBackupService;
import org.ssssssss.magicapi.core.service.MagicDynamicRegistry;
import org.ssssssss.magicapi.core.service.MagicResourceStorage;
import org.ssssssss.magicapi.utils.JsonUtils;
import org.ssssssss.magicapi.utils.WebUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class MagicBackupController extends MagicController implements MagicExceptionHandler {

	private final MagicBackupService service;

	public MagicBackupController(MagicConfiguration configuration) {
		super(configuration);
		this.service = configuration.getMagicBackupService();
	}

	@GetMapping("/backups")
	@ResponseBody
	public JsonBean<List<Backup>> backups(Long timestamp) {
		if(service == null){
			return new JsonBean<>(Collections.emptyList());
		}
		return new JsonBean<>(service.backupList(timestamp == null ? System.currentTimeMillis() : timestamp).stream()
				.filter(this::canReadBackup).collect(Collectors.toList()));
	}

	@GetMapping("/backup/{id}")
	@ResponseBody
	public JsonBean<List<Backup>> backups(@PathVariable("id") String id) {
		if(service == null || StringUtils.isBlank(id)){
			return new JsonBean<>(Collections.emptyList());
		}
		return new JsonBean<>(service.backupById(id).stream().filter(this::canReadBackup).collect(Collectors.toList()));
	}

	@PostMapping("/backup/rollback")
	@ResponseBody
	public JsonBean<?> rollback(String id, Long timestamp) throws IOException {
		Backup backup = requireBackup(id, timestamp);
		if("full".equals(id)){
			isTrue("full".equals(backup.getType()), PARAMETER_INVALID);
			isTrue(canReadBackup(backup), PERMISSION_INVALID);
			service.doBackupAll("还原全量备份前，系统自动全量备份", WebUtils.currentUserName());
			configuration.getMagicAPIService().upload(new ByteArrayInputStream(backup.getContent()), Constants.UPLOAD_MODE_FULL);
			return new JsonBean<>(true);
		}
		MagicResourceStorage<?> storage = backupStorage(backup);
		isTrue(canReadBackup(backup), PERMISSION_INVALID);
		Map<String, String> options = Collections.emptyMap();
		if(backup.getType().endsWith("-group")){
			Group group = readGroupBackup(backup, storage);
			return new JsonBean<>(storage.restoreGroupBackup(group, configuration.getMagicResourceService(), options));
		}
		return new JsonBean<>(restoreFileBackup(backup, storage, options));
	}

	@GetMapping("/backup")
	@ResponseBody
	public JsonBean<?> backup(Long timestamp, String id, @RequestParam(value = "metadata", defaultValue = "false") boolean metadata) {
		Backup backup = requireBackup(id, timestamp);
		MagicResourceStorage<?> storage = backupStorage(backup);
		isTrue(canReadBackup(backup), PERMISSION_INVALID);
		if (backup.getType().endsWith("-group")) {
			Group group = readGroupBackup(backup, storage);
			return new JsonBean<>(metadata ? group : null);
		}
		MagicEntity entity = readFileBackup(backup, storage);
		return new JsonBean<>(metadata ? entity : entity.getScript());
	}

	@PostMapping("/backup/full")
	@ResponseBody
	public JsonBean<Boolean> doBackup() throws IOException {
		notNull(service, BACKUP_NOT_ENABLED);
		isTrue(configuration.getMagicResourceService().allowRead("full"), PERMISSION_INVALID);
		service.doBackupAll("主动全量备份", WebUtils.currentUserName());
		return new JsonBean<>(true);
	}

	private Backup requireBackup(String id, Long timestamp) {
		notNull(service, BACKUP_NOT_ENABLED);
		notBlank(id, PARAMETER_INVALID);
		notNull(timestamp, PARAMETER_INVALID);
		Backup backup = service.backupInfo(id, timestamp);
		notNull(backup, FILE_NOT_FOUND);
		isTrue(id.equals(backup.getId()), PARAMETER_INVALID);
		notBlank(backup.getType(), NOT_SUPPORTED_GROUP_TYPE);
		notNull(backup.getContent(), PARAMETER_INVALID);
		return backup;
	}

	private MagicResourceStorage<?> backupStorage(Backup backup) {
		String type = backup.getType();
		String resourceType = type.endsWith("-group") ? type.substring(0, type.length() - 6) : type;
		MagicResourceStorage<?> storage = configuration.getMagicDynamicRegistries().stream()
				.map(MagicDynamicRegistry::getMagicResourceStorage)
				.filter(it -> it.folder().equals(resourceType))
				.findFirst().orElse(null);
		notNull(storage, NOT_SUPPORTED_GROUP_TYPE);
		return storage;
	}

	private Group readGroupBackup(Backup backup, MagicResourceStorage<?> storage) {
		Group group = JsonUtils.readValue(backup.getContent(), Group.class);
		notNull(group, GROUP_NOT_FOUND);
		isTrue(backup.getId().equals(group.getId()), PARAMETER_INVALID);
		isTrue(storage.folder().equals(group.getType()), NOT_SUPPORTED_GROUP_TYPE);
		return group;
	}

	private <T extends MagicEntity> T readFileBackup(Backup backup, MagicResourceStorage<T> storage) {
		T entity = storage.readBackup(backup.getContent());
		notNull(entity, FILE_NOT_FOUND);
		isTrue(backup.getId().equals(entity.getId()), PARAMETER_INVALID);
		Group group = configuration.getMagicResourceService().getGroup(entity.getGroupId());
		isTrue(group == null || storage.folder().equals(group.getType()), NOT_SUPPORTED_GROUP_TYPE);
		return entity;
	}

	private <T extends MagicEntity> Object restoreFileBackup(Backup backup, MagicResourceStorage<T> storage,
			Map<String, String> options) {
		return storage.restoreBackup(readFileBackup(backup, storage), configuration.getMagicResourceService(), options);
	}

	private boolean canReadBackup(Backup backup) {
		if (backup == null) return false;
		String type = "full".equals(backup.getId()) ? "full" : backup.getType();
		if (type != null && type.endsWith("-group")) type = type.substring(0, type.length() - 6);
		return configuration.getMagicResourceService().allowRead(type);
	}
}
