package org.ssssssss.magicapi.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.ssssssss.magicapi.core.config.Constants;
import org.ssssssss.magicapi.core.config.JsonCodeConstants;
import org.ssssssss.magicapi.core.event.EventAction;
import org.ssssssss.magicapi.core.event.FileEvent;
import org.ssssssss.magicapi.core.event.GroupEvent;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.model.MagicNotify;
import org.ssssssss.magicapi.core.resource.FileResource;
import org.ssssssss.magicapi.core.service.impl.ApiInfoMagicResourceStorage;
import org.ssssssss.magicapi.core.service.impl.DefaultMagicResourceService;
import org.ssssssss.magicapi.function.service.FunctionInfoMagicResourceStorage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.ssssssss.magicapi.git.GitWorkspaceFixture.api;

@Timeout(60)
class GitNotificationRegressionTest {
    @TempDir Path directory;

    @Test
    void missingGroupNotificationsDoNotFreezeHealthyWorkspaceOrBreakNextStartup() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            for (EventAction action : Arrays.asList(EventAction.CREATE, EventAction.SAVE, EventAction.MOVE, EventAction.DELETE)) {
                assertFalse(workspace.service.processNotify(groupNotice("missing-group", action)));
                assertHealthy(workspace);
                assertEquals("return 1;", workspace.service.file("a").getScript());
                assertNotNull(workspace.registry.getMapping("GET:/orders/first"));
            }
            workspace.save(workspace.service, "a", "return 11;");
            assertEquals("return 11;", workspace.service.file("a").getScript());
            workspace.repo.close();
            try (GitRepo restarted = new GitRepo(workspace.localPath.toString(), workspace.properties)) {
                restarted.setupRepo();
                GitResource resource = new GitResource(false, workspace.localPath.toFile(), workspace.localPath.toString(), restarted);
                DefaultMagicResourceService service = new DefaultMagicResourceService(resource,
                        Collections.singletonList(new ApiInfoMagicResourceStorage("")), event -> { });
                // 真实启动入口会捕获异常，必须检查资源确实已加载，不能仅断言没有抛出。
                service.onApplicationEvent(null);
                assertNotNull(service.getGroup("g"));
                assertEquals("return 11;", service.file("a").getScript());
                assertFalse(restarted.getCoordinator().isInconsistent());
            }
        }
    }

    @Test
    void missingFileNotificationsDoNotFreezeOrPublishNullEntities() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            for (EventAction action : Arrays.asList(EventAction.CREATE, EventAction.SAVE, EventAction.MOVE, EventAction.DELETE)) {
                assertFalse(workspace.service.processNotify(fileNotice("missing-file", action)));
                assertHealthy(workspace);
                assertNotNull(workspace.registry.getMapping("GET:/orders/first"));
            }
        }
    }

    @Test
    void missingNotificationReloadRemovesRoutesBeforeTheirDeletionNotificationArrives() throws Exception {
        List<MagicNotify> notices = Arrays.asList(groupNotice("missing-group", EventAction.SAVE),
                fileNotice("missing-file", EventAction.SAVE));
        for (int index = 0; index < notices.size(); index++) {
            try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory.resolve("notice-" + index))) {
                Files.delete(workspace.localPath.resolve("api/Orders/First.ms"));
                assertFalse(workspace.service.processNotify(notices.get(index)));
                assertNull(workspace.service.file("a"));
                assertNull(workspace.registry.getMapping("GET:/orders/first"));
                assertNotNull(workspace.registry.getMapping("GET:/orders/second"));
                assertFalse(workspace.service.processNotify(fileNotice("a", EventAction.DELETE)));
                assertHealthy(workspace);
            }
        }
    }

    @Test
    void missingFileOnDiskReloadAlsoRemovesOtherDeletedRoutes() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            Files.delete(workspace.localPath.resolve("api/Orders/First.ms"));
            Files.delete(workspace.localPath.resolve("api/Orders/Second.ms"));
            assertTrue(workspace.service.processNotify(fileNotice("a", EventAction.SAVE)));
            assertNull(workspace.registry.getMapping("GET:/orders/first"));
            assertNull(workspace.registry.getMapping("GET:/orders/second"));
            assertFalse(workspace.service.processNotify(fileNotice("b", EventAction.DELETE)));
            assertHealthy(workspace);
        }
    }

    @Test
    void movedGroupReloadRemovesDeletedRoutesAndPreservesRemainingRoutes() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            DefaultMagicResourceService external = externalService(workspace);
            Group renamed = external.getGroup("g").copy();
            renamed.setName("Moved-orders");
            renamed.setPath("moved");
            assertTrue(external.saveGroup(renamed));
            Files.delete(workspace.localPath.resolve("api/Moved-orders/First.ms"));
            assertTrue(workspace.service.processNotify(groupNotice("g", EventAction.MOVE)));
            assertNull(workspace.service.file("a"));
            assertNull(workspace.registry.getMapping("GET:/orders/first"));
            assertNull(workspace.registry.getMapping("GET:/moved/first"));
            assertNotNull(workspace.registry.getMapping("GET:/moved/second"));
            assertHealthy(workspace);
        }
    }

    @Test
    void groupUpdateRemovesDeletedChildrenWhileTheGroupDirectoryStillExists() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            Files.delete(workspace.localPath.resolve("api/Orders/First.ms"));
            assertTrue(workspace.service.processNotify(groupNotice("g", EventAction.SAVE)));
            assertNotNull(workspace.service.getGroup("g"));
            assertNull(workspace.service.file("a"));
            assertNull(workspace.registry.getMapping("GET:/orders/first"));
            assertNotNull(workspace.registry.getMapping("GET:/orders/second"));
            assertHealthy(workspace);
        }
    }

    @Test
    void groupDeletionAfterChildMoveDoesNotUnregisterTheNewRoute() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            DefaultMagicResourceService external = externalService(workspace);
            Group destination = new Group("kept", "Kept");
            destination.setType("api");
            destination.setParentId("0");
            destination.setPath("kept");
            assertTrue(external.saveGroup(destination));
            ApiInfo moved = ((ApiInfo) external.file("a")).copy();
            moved.setGroupId("kept");
            assertTrue(external.saveFile(moved));
            deleteGroupOnDisk(workspace);
            assertTrue(workspace.service.processNotify(groupNotice("g", EventAction.SAVE)));
            assertNull(workspace.service.getGroup("g"));
            assertEquals("kept", workspace.service.file("a").getGroupId());
            assertNull(workspace.registry.getMapping("GET:/orders/first"));
            assertNotNull(workspace.registry.getMapping("GET:/kept/first"));
            assertHealthy(workspace);
        }
    }

    @Test
    void notificationReloadEventsCannotBeBroadcastBackToTheCluster() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            assertFalse(workspace.service.processNotify(groupNotice("missing-group", EventAction.SAVE)));
            assertEquals(1, workspace.clears.get());
            assertFalse(workspace.events.isEmpty());
            assertTrue(workspace.events.stream().allMatch(event -> Constants.EVENT_SOURCE_NOTIFY.equals(event.getSource())));
            assertHealthy(workspace);
        }
    }

    @Test
    void notificationReloadPublicationFailureStillFreezesTheWorkspace() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.failPublication = EventAction.LOAD;
            assertThrows(IllegalStateException.class,
                    () -> workspace.service.processNotify(groupNotice("missing-group", EventAction.SAVE)));
            assertTrue(workspace.repo.getCoordinator().isInconsistent());
            assertTrue(Files.exists(workspace.localPath.resolve(".git/magic-api-workspace-inconsistent")));
            assertEquals("return 1;", workspace.service.file("a").getScript());
            InvalidArgumentException failure = assertThrows(InvalidArgumentException.class,
                    () -> workspace.save(workspace.service, "a", "return 9;"));
            assertEquals(JsonCodeConstants.WORKSPACE_INCONSISTENT.getCode(), failure.getCode());
            assertEquals(0, workspace.repo.fetchCalls.get());
            assertEquals(0, workspace.repo.pushCalls.get());
        }
    }

    @Test
    void ignoredNotificationDuringInitializationIsNotAPartialWriteFailure() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            Path functions = workspace.localPath.resolve("function");
            assertFalse(Files.exists(functions));
            DefaultMagicResourceService initializing = new DefaultMagicResourceService(workspace.resource,
                    Arrays.asList(new ApiInfoMagicResourceStorage(""), new FunctionInfoMagicResourceStorage()), event -> { });
            // Redis 监听可能早于启动加载；创建空类型目录不应把“忽略通知”解释成写入失败。
            assertFalse(initializing.processNotify(groupNotice("missing-group", EventAction.CREATE)));
            assertTrue(Files.isDirectory(functions));
            assertHealthy(workspace);
            initializing.onApplicationEvent(null);
            assertEquals("return 1;", initializing.file("a").getScript());
            assertFalse(workspace.repo.getCoordinator().isInconsistent());
        }
    }

    @Test
    void delayedGroupCreationCanBeAppliedAfterTheLocalResourceArrives() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            assertFalse(workspace.service.processNotify(groupNotice("later", EventAction.CREATE)));
            DefaultMagicResourceService external = externalService(workspace);
            Group group = new Group("later", "Later");
            group.setType("api");
            group.setParentId("0");
            group.setPath("later");
            assertTrue(external.saveGroup(group));
            ApiInfo api = api("later-api", "Later-API", "query", "return 3;");
            api.setGroupId("later");
            assertTrue(external.saveFile(api));
            assertTrue(workspace.service.processNotify(groupNotice("later", EventAction.CREATE)));
            assertEquals("return 3;", workspace.service.file("later-api").getScript());
            assertNotNull(workspace.registry.getMapping("GET:/later/query"));
            assertHealthy(workspace);
        }
    }

    @Test
    void repeatedGroupDeletionIsIdempotentAndDoesNotFreezeTheWorkspace() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            deleteGroupOnDisk(workspace);
            assertTrue(workspace.service.processNotify(groupNotice("g", EventAction.DELETE)));
            assertFalse(workspace.service.processNotify(groupNotice("g", EventAction.DELETE)));
            assertNull(workspace.service.getGroup("g"));
            assertNull(workspace.service.file("a"));
            assertNull(workspace.registry.getMapping("GET:/orders/first"));
            assertNull(workspace.registry.getMapping("GET:/orders/second"));
            assertHealthy(workspace);
        }
    }

    @Test
    void groupUpdateArrivingAfterDeletionRemovesOldRoutesWithoutFreezing() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            deleteGroupOnDisk(workspace);
            assertTrue(workspace.service.processNotify(groupNotice("g", EventAction.SAVE)));
            assertFalse(workspace.service.processNotify(groupNotice("g", EventAction.MOVE)));
            assertNull(workspace.service.getGroup("g"));
            assertNull(workspace.service.file("a"));
            assertNull(workspace.registry.getMapping("GET:/orders/first"));
            assertNull(workspace.registry.getMapping("GET:/orders/second"));
            assertHealthy(workspace);
        }
    }

    @Test
    void fileUpdateArrivingAfterDeletionRemovesOldRouteWithoutFreezing() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            Files.delete(workspace.localPath.resolve("api/Orders/First.ms"));
            assertTrue(workspace.service.processNotify(fileNotice("a", EventAction.MOVE)));
            assertFalse(workspace.service.processNotify(fileNotice("a", EventAction.SAVE)));
            assertFalse(workspace.service.processNotify(fileNotice("a", EventAction.DELETE)));
            assertNull(workspace.service.file("a"));
            assertNull(workspace.registry.getMapping("GET:/orders/first"));
            assertNotNull(workspace.registry.getMapping("GET:/orders/second"));
            assertHealthy(workspace);
        }
    }

    @Test
    void groupMoveNotificationRebuildsPathsFromTheNewDirectory() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            DefaultMagicResourceService external = externalService(workspace);
            Group renamed = external.getGroup("g").copy();
            renamed.setName("Moved-orders");
            renamed.setPath("moved");
            assertTrue(external.saveGroup(renamed));
            assertTrue(workspace.service.processNotify(groupNotice("g", EventAction.MOVE)));
            assertEquals("Moved-orders", workspace.service.getGroup("g").getName());
            assertNull(workspace.registry.getMapping("GET:/orders/first"));
            assertNotNull(workspace.registry.getMapping("GET:/moved/first"));
            assertNotNull(workspace.registry.getMapping("GET:/moved/second"));
            assertHealthy(workspace);
        }
    }

    @Test
    void readonlyWorkspaceCanApplyDeletionNotificationsWithoutChangingDisk() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            List<GroupEvent> deletions = new ArrayList<>();
            GitResource readonly = new GitResource(true, workspace.localPath.toFile(), workspace.localPath.toString(), workspace.repo);
            DefaultMagicResourceService service = new DefaultMagicResourceService(readonly,
                    Collections.singletonList(new ApiInfoMagicResourceStorage("")), event -> {
                if (event instanceof GroupEvent) deletions.add((GroupEvent) event);
                if (event instanceof FileEvent) assertNotNull(((FileEvent) event).getEntity());
            });
            service.refreshLocal();
            deleteGroupOnDisk(workspace);
            assertTrue(service.processNotify(groupNotice("g", EventAction.DELETE)));
            assertFalse(service.processNotify(groupNotice("g", EventAction.DELETE)));
            assertEquals(1, deletions.size());
            assertEquals(2, deletions.get(0).getEntities().size());
            assertNull(service.getGroup("g"));
            assertFalse(Files.exists(workspace.localPath.resolve("api/Orders")));
            assertHealthy(workspace);
        }
    }

    private static MagicNotify groupNotice(String id, EventAction action) {
        return new MagicNotify("other-instance", id, action, "api");
    }

    private static MagicNotify fileNotice(String id, EventAction action) {
        return new MagicNotify("other-instance", id, action, Constants.EVENT_TYPE_FILE);
    }

    private static DefaultMagicResourceService externalService(GitWorkspaceFixture workspace) {
        return GitWorkspaceFixture.service(new FileResource(workspace.localPath.toFile(), false, workspace.localPath.toString()), new AtomicInteger());
    }

    private static void deleteGroupOnDisk(GitWorkspaceFixture workspace) {
        // 模拟其他实例完成持久化后才到达的通知，仅操作本用例的临时工作区。
        assertTrue(new FileResource(workspace.localPath.resolve("api/Orders").toFile(), false, workspace.localPath.toString()).delete());
    }

    private static void assertHealthy(GitWorkspaceFixture workspace) {
        assertFalse(workspace.repo.getCoordinator().isInconsistent());
        assertFalse(Files.exists(workspace.localPath.resolve(".git/magic-api-workspace-inconsistent")));
        assertEquals(0, workspace.repo.fetchCalls.get());
        assertEquals(0, workspace.repo.pushCalls.get());
    }
}
