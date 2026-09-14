package org.ssssssss.magicapi.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.ssssssss.magicapi.backup.service.MagicDatabaseBackupService;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.event.EventAction;
import org.ssssssss.magicapi.core.event.FileEvent;
import org.ssssssss.magicapi.core.event.GroupEvent;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.resource.FileResource;
import org.ssssssss.magicapi.core.service.MagicResourceService;
import org.ssssssss.magicapi.core.service.impl.DefaultMagicResourceService;
import org.ssssssss.magicapi.core.servlet.MagicRequestContextHolder;
import org.ssssssss.magicapi.task.model.TaskInfo;
import org.ssssssss.magicapi.task.service.TaskInfoMagicResourceStorage;
import org.ssssssss.magicapi.utils.JsonUtils;
import org.ssssssss.magicapi.utils.WebUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Timeout(60)
class TaskBackupContractTest {
    @TempDir Path directory;
    private final List<Object[]> rows = new ArrayList<>();
    private MagicResourceService previousResources;
    private MagicRequestContextHolder previousRequest;
    private AnnotationConfigApplicationContext events;

    @BeforeEach void setup() {
        previousResources = MagicConfiguration.getMagicResourceService();
        previousRequest = WebUtils.magicRequestContextHolder;
        WebUtils.magicRequestContextHolder = mock(MagicRequestContextHolder.class);
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override public int update(String sql, Object... arguments) { rows.add(arguments.clone()); return 1; }
        };
        events = new AnnotationConfigApplicationContext();
        events.registerBean(MagicDatabaseBackupService.class,
                () -> new MagicDatabaseBackupService(jdbc, "lowcode_magic_api_backup"));
        events.refresh();
        DefaultMagicResourceService resources = new DefaultMagicResourceService(
                new FileResource(directory.toFile(), false, directory.toString()),
                Collections.singletonList(new TaskInfoMagicResourceStorage()), events);
        new MagicConfiguration().setMagicResourceService(resources);
        resources.refreshLocal();
    }

    @AfterEach void cleanup() {
        if (events != null) events.close();
        new MagicConfiguration().setMagicResourceService(previousResources);
        WebUtils.magicRequestContextHolder = previousRequest;
    }

    @ParameterizedTest @EnumSource(value = EventAction.class, names = {"CREATE", "SAVE", "MOVE"})
    void taskAndGroupEventsStillCreateOrdinaryBackups(EventAction action) {
        TaskInfo task = new TaskInfo();
        task.setId("0123456789abcdef0123456789abcdef");
        task.setName("定时任务");
        task.setCron("0 0 0 * * ?");
        task.setScript("return '执行任务';");
        Group group = new Group();
        group.setId("abcdef0123456789abcdef0123456789");
        group.setName("任务目录");
        group.setType("task");
        events.publishEvent(new FileEvent("task", action, task));
        events.publishEvent(new GroupEvent("task", action, group));
        assertEquals(2, rows.size());
        assertEquals(task.getId(), rows.get(0)[0]);
        assertEquals("task", rows.get(0)[3]);
        assertArrayEquals(JsonUtils.toJsonBytes(task), (byte[]) rows.get(0)[6]);
        assertEquals(group.getId(), rows.get(1)[0]);
        assertEquals("task-group", rows.get(1)[3]);
        assertArrayEquals(JsonUtils.toJsonBytes(group), (byte[]) rows.get(1)[6]);
    }
}
