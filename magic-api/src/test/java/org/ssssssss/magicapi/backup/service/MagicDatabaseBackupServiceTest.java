package org.ssssssss.magicapi.backup.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.ssssssss.magicapi.backup.model.Backup;
import org.ssssssss.magicapi.backup.web.MagicBackupController;
import org.ssssssss.magicapi.core.config.Constants;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.event.EventAction;
import org.ssssssss.magicapi.core.event.FileEvent;
import org.ssssssss.magicapi.core.event.GroupEvent;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.resource.FileResource;
import org.ssssssss.magicapi.core.service.AbstractMagicDynamicRegistry;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.core.service.MagicResourceStorage;
import org.ssssssss.magicapi.core.service.impl.ApiInfoMagicResourceStorage;
import org.ssssssss.magicapi.core.service.impl.DefaultMagicResourceService;
import org.ssssssss.magicapi.core.servlet.MagicRequestContextHolder;
import org.ssssssss.magicapi.function.model.FunctionInfo;
import org.ssssssss.magicapi.function.service.FunctionInfoMagicResourceStorage;
import org.ssssssss.magicapi.utils.JsonUtils;
import org.ssssssss.magicapi.utils.WebUtils;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 通过真实 Spring 监听校验类型声明、事件来源和数据库失败边界。 */
@Timeout(60)
@ExtendWith(OutputCaptureExtension.class)
class MagicDatabaseBackupServiceTest {
    private static final String ID = "0123456789abcdef0123456789abcdef";
    @TempDir Path directory;
    private final RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
    private AnnotationConfigApplicationContext events;
    private MagicResourceService previousResources;
    private MagicRequestContextHolder previousRequest;

    @BeforeEach void setup() {
        previousResources = MagicConfiguration.getMagicResourceService();
        previousRequest = WebUtils.magicRequestContextHolder;
        WebUtils.magicRequestContextHolder = mock(MagicRequestContextHolder.class);
        events = new AnnotationConfigApplicationContext();
        events.registerBean(MagicDatabaseBackupService.class,
                () -> new MagicDatabaseBackupService(jdbc, "lowcode_magic_api_backup"));
        events.refresh();
        ApiInfoMagicResourceStorage versioned = new ApiInfoMagicResourceStorage("") {
            @Override public String folder() { return "versioned"; }
            @Override public boolean supportsEventBackup() { return false; }
        };
        DefaultMagicResourceService resources = new DefaultMagicResourceService(
                new FileResource(directory.toFile(), false, directory.toString()),
                Arrays.asList(new ApiInfoMagicResourceStorage(""), new FunctionInfoMagicResourceStorage(), versioned), events);
        new MagicConfiguration().setMagicResourceService(resources);
        resources.refreshLocal();
    }

    @AfterEach void cleanup() {
        if (events != null) events.close();
        new MagicConfiguration().setMagicResourceService(previousResources);
        WebUtils.magicRequestContextHolder = previousRequest;
    }

    static Stream<Arguments> ordinaryEvents() {
        return Stream.of("api", "function").flatMap(type ->
                Stream.of(EventAction.CREATE, EventAction.SAVE, EventAction.MOVE).map(action -> Arguments.of(type, action)));
    }

    @ParameterizedTest @MethodSource("ordinaryEvents")
    void ordinaryFilesKeepIdentityTypeAndContent(String type, EventAction action) {
        MagicEntity entity = entity(type);
        events.publishEvent(new FileEvent(type, action, entity));
        assertEquals(1, jdbc.attempts.size());
        Object[] row = jdbc.attempts.get(0);
        assertEquals(ID, row[0]);
        assertEquals(type, row[3]);
        assertEquals(entity.getName(), row[4]);
        assertArrayEquals(JsonUtils.toJsonBytes(entity), (byte[]) row[6]);
    }

    @ParameterizedTest @MethodSource("ordinaryEvents")
    void ordinaryGroupsUseRawTypeForLookupAndKeepBackupSuffix(String type, EventAction action) {
        Group group = group(type);
        events.publishEvent(new GroupEvent(type, action, group));
        assertEquals(1, jdbc.attempts.size());
        Object[] row = jdbc.attempts.get(0);
        assertEquals(ID, row[0]);
        assertEquals(type + "-group", row[3]);
        assertEquals(group.getName(), row[4]);
        assertArrayEquals(JsonUtils.toJsonBytes(group), (byte[]) row[6]);
    }

    @Test void excludedTypeSkipsBothFilesAndGroupsForEveryWritingAction() {
        for (EventAction action : Arrays.asList(EventAction.CREATE, EventAction.SAVE, EventAction.MOVE)) {
            events.publishEvent(new FileEvent("versioned", action, entity("versioned")));
            events.publishEvent(new GroupEvent("versioned", action, group("versioned")));
        }
        assertTrue(jdbc.attempts.isEmpty());
    }

    @Test void notifyAndNonWritingEventsRemainIgnoredBeforeTypeLookup() {
        for (EventAction action : EventAction.values()) {
            if (Arrays.asList(EventAction.CREATE, EventAction.SAVE, EventAction.MOVE).contains(action)) {
                events.publishEvent(new FileEvent("unknown", action, entity("unknown"), Constants.EVENT_SOURCE_NOTIFY));
                GroupEvent group = new GroupEvent("unknown", action, group("unknown"));
                group.setSource(Constants.EVENT_SOURCE_NOTIFY);
                events.publishEvent(group);
            } else {
                events.publishEvent(new FileEvent("unknown", action, entity("unknown")));
                events.publishEvent(new GroupEvent("unknown", action, group("unknown")));
            }
        }
        assertTrue(jdbc.attempts.isEmpty());
    }

    @Test void unknownResourceTypeFailsInsteadOfPretendingToOptOut() {
        assertThrows(InvalidArgumentException.class,
                () -> events.publishEvent(new FileEvent("unknown", EventAction.SAVE, entity("unknown"))));
        assertThrows(InvalidArgumentException.class,
                () -> events.publishEvent(new GroupEvent("unknown", EventAction.SAVE, group("unknown"))));
        assertTrue(jdbc.attempts.isEmpty());
    }

    @Test void ordinarySqlErrorsRemainVisibleAndLongIdsAreNotSilentlySkipped(CapturedOutput output) {
        MagicEntity entity = entity("api");
        entity.setId(String.join("", Collections.nCopies(68, "a")));
        jdbc.fail = true;
        events.publishEvent(new FileEvent("api", EventAction.SAVE, entity));
        assertEquals(1, jdbc.attempts.size());
        assertEquals(entity.getId(), jdbc.attempts.get(0)[0]);
        assertTrue(output.getAll().contains("备份失败"));
        assertTrue(output.getAll().contains("ORA-12899"));
    }

    @Test void ordinaryBackupKeepsScriptResponseAndRestoresFullEntity() throws Exception {
        ApiInfo api = (ApiInfo) entity("api");
        api.setGroupId("abcdefabcdefabcdefabcdefabcdefab");
        api.setMethod("POST");
        api.setPath("restored");
        Backup backup = new Backup(ID, "api", api.getName(), JsonUtils.toJsonBytes(api));
        MagicBackupController controller = controller(backup, new ApiInfoMagicResourceStorage(""));
        Group parent = group("api");
        parent.setId(api.getGroupId());
        assertTrue(MagicConfiguration.getMagicResourceService().saveGroup(parent));
        assertEquals(api.getScript(), controller.backup(1L, ID, false).getData());
        ApiInfo metadata = (ApiInfo) controller.backup(1L, ID, true).getData();
        assertEquals("POST", metadata.getMethod());
        assertEquals("restored", metadata.getPath());
        assertEquals(Boolean.TRUE, controller.rollback(ID, 1L).getData());
        ApiInfo restored = (ApiInfo) MagicConfiguration.getMagicResourceService().file(ID);
        assertEquals(api.getScript(), restored.getScript());
        assertEquals("POST", restored.getMethod());
        assertEquals(api.getGroupId(), restored.getGroupId());
    }

    @Test void managedBackupsUseDedicatedDecoderAndRestoreHooks() throws Exception {
        ManagedBackupStorage storage = new ManagedBackupStorage();
        ApiInfo api = (ApiInfo) entity("api");
        api.setGroupId("managed:0");
        MagicBackupController controller = controller(
                new Backup(ID, storage.folder(), api.getName(), JsonUtils.toJsonBytes(api)), storage);
        assertEquals(api.getScript(), controller.backup(1L, ID, false).getData());
        assertEquals(ID, ((ApiInfo) controller.backup(1L, ID, true).getData()).getId());
        assertEquals(Collections.singletonMap("restored", true), controller.rollback(ID, 1L).getData());
        assertTrue(storage.options.isEmpty());
        assertEquals(api.getScript(), storage.restored.getScript());

        Group group = group(storage.folder());
        controller = controller(new Backup(ID, "managed-group", group.getName(), JsonUtils.toJsonBytes(group)), storage);
        assertNull(controller.backup(1L, ID, false).getData());
        assertEquals(group.getType(), ((Group) controller.backup(1L, ID, true).getData()).getType());
        assertEquals(Collections.singletonMap("restored", true), controller.rollback(ID, 1L).getData());
        assertTrue(storage.options.isEmpty());
        assertEquals(ID, storage.restoredGroup.getId());
    }

    @Test void backupIdentityAndTypeMismatchFailBeforeRestore() {
        ApiInfo api = (ApiInfo) entity("api");
        api.setId("different-entity");
        Backup backup = new Backup(ID, "api", api.getName(), JsonUtils.toJsonBytes(api));
        MagicBackupController controller = controller(backup, new ApiInfoMagicResourceStorage(""));
        assertThrows(InvalidArgumentException.class, () -> controller.backup(1L, ID, true));
        assertThrows(InvalidArgumentException.class, () -> controller.rollback(ID, 1L));
        backup.setType("unknown");
        assertThrows(InvalidArgumentException.class, () -> controller.backup(1L, ID, false));
        Group group = group("function");
        backup.setType("api-group");
        backup.setContent(JsonUtils.toJsonBytes(group));
        assertThrows(InvalidArgumentException.class, () -> controller.rollback(ID, 1L));
        backup.setId("different-backup");
        assertThrows(InvalidArgumentException.class, () -> controller.backup(1L, ID, true));
    }

    @Test void managedBackupReadPermissionStillGuardsMetadataAndRollback() {
        ManagedBackupStorage storage = new ManagedBackupStorage();
        ApiInfo api = (ApiInfo) entity("api");
        api.setGroupId("managed:0");
        MagicBackupController controller = controller(
                new Backup(ID, storage.folder(), api.getName(), JsonUtils.toJsonBytes(api)), storage);
        storage.readable = false;
        assertThrows(InvalidArgumentException.class, () -> controller.backup(1L, ID, false));
        assertThrows(InvalidArgumentException.class, () -> controller.backup(1L, ID, true));
        assertThrows(InvalidArgumentException.class, () -> controller.rollback(ID, 1L));
        assertNull(storage.restored);
    }

    private MagicBackupController controller(Backup backup, MagicResourceStorage<ApiInfo> storage) {
        MagicConfiguration configuration = new MagicConfiguration();
        MagicBackupService lookup = mock(MagicBackupService.class);
        when(lookup.backupInfo(backup.getId(), 1L)).thenReturn(backup);
        configuration.setMagicBackupService(lookup);
        configuration.setMagicDynamicRegistries(Collections.singletonList(new AbstractMagicDynamicRegistry<ApiInfo>(storage) { }));
        Path root = directory.resolve("restore-" + storage.folder());
        DefaultMagicResourceService resources = new DefaultMagicResourceService(
                new FileResource(root.toFile(), false, root.toString()), Collections.singletonList(storage), events);
        configuration.setMagicResourceService(resources);
        resources.refreshLocal();
        return new MagicBackupController(configuration);
    }

    private static final class ManagedBackupStorage extends ApiInfoMagicResourceStorage {
        private ApiInfo restored;
        private Group restoredGroup;
        private Map<String, String> options;
        private boolean readable = true;
        ManagedBackupStorage() { super(""); }
        @Override public String folder() { return "managed"; }
        @Override public boolean allowRoot() { return true; }
        @Override public boolean requirePath() { return false; }
        @Override public boolean allowRead() { return readable; }
        @Override public ApiInfo read(byte[] content) {
            throw new IllegalArgumentException("物理文件解析不接受备份 JSON");
        }
        @Override public ApiInfo readBackup(byte[] content) { return JsonUtils.readValue(content, ApiInfo.class); }
        @Override public Object restoreBackup(ApiInfo entity, MagicResourceService resources, Map<String, String> options) {
            this.restored = entity;
            this.options = new LinkedHashMap<>(options);
            return Collections.singletonMap("restored", true);
        }
        @Override public Object restoreGroupBackup(Group group, MagicResourceService resources, Map<String, String> options) {
            this.restoredGroup = group;
            this.options = new LinkedHashMap<>(options);
            return Collections.singletonMap("restored", true);
        }
    }

    private static MagicEntity entity(String type) {
        MagicEntity entity = "function".equals(type) ? new FunctionInfo() : new ApiInfo();
        entity.setId(ID);
        entity.setName("原始名称");
        entity.setGroupId(type + ":0");
        entity.setScript("return '中文';");
        return entity;
    }

    private static Group group(String type) {
        Group group = new Group();
        group.setId(ID);
        group.setType(type);
        group.setParentId("0");
        group.setName("业务目录");
        return group;
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<Object[]> attempts = new ArrayList<>();
        private boolean fail;
        @Override public int update(String sql, Object... arguments) {
            attempts.add(arguments.clone());
            if (fail) throw new UncategorizedSQLException("备份写入", sql, new SQLException("ORA-12899", "72000", 12899));
            return 1;
        }
    }
}
