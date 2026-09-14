package org.ssssssss.magicapi.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.ssssssss.magicapi.core.servlet.MagicRequestContextHolder;
import org.ssssssss.magicapi.utils.WebUtils;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.ssssssss.magicapi.core.event.EventAction;
import org.ssssssss.magicapi.core.event.GroupEvent;
import org.ssssssss.magicapi.core.event.MagicEvent;
import org.ssssssss.magicapi.core.config.Constants;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.resource.FileResource;
import org.ssssssss.magicapi.core.service.impl.ApiInfoMagicResourceStorage;
import org.ssssssss.magicapi.core.service.impl.DefaultMagicResourceService;
import org.ssssssss.magicapi.utils.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Timeout(60)
class ResourceRefreshTest {
    @TempDir Path directory;
    MagicRequestContextHolder previousContext;

    @BeforeEach void context() {
        previousContext = WebUtils.magicRequestContextHolder;
        WebUtils.magicRequestContextHolder = mock(MagicRequestContextHolder.class);
    }
    @AfterEach void restoreContext() { WebUtils.magicRequestContextHolder = previousContext; }

    @Test
    void failedReloadKeepsLastTreeAndDetails() throws Exception {
        DefaultMagicResourceService service = new DefaultMagicResourceService(
                new FileResource(directory.toFile(), false, directory.toString()),
                Collections.singletonList(new ApiInfoMagicResourceStorage("")), event -> { });
        service.refreshLocal();
        service.saveGroup(ResourceRenameTest.group("g1", "Orders", "orders"));
        service.saveFile(ResourceRenameTest.api("a", "First", "first", "return 1;"));
        Files.write(directory.resolve("api/Orders/First.ms"), "{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(RuntimeException.class, service::refreshLocal);
        assertEquals("First", service.file("a").getName());
        assertEquals("return 1;", service.file("a").getScript());
        assertEquals("Orders", service.getGroup("g1").getName());
    }

    @Test
    void cyclicGroupMetadataFailsReloadWithoutReplacingLastCache() throws Exception {
        DefaultMagicResourceService service = new DefaultMagicResourceService(
                new FileResource(directory.toFile(), false, directory.toString()),
                Collections.singletonList(new ApiInfoMagicResourceStorage("")), event -> { });
        service.refreshLocal();
        service.saveGroup(ResourceRenameTest.group("g1", "Orders", "orders"));
        service.saveFile(ResourceRenameTest.api("a", "First", "first", "return 1;"));
        Group cyclic = service.getGroup("g1").copy();
        cyclic.setParentId("g1");
        Files.write(directory.resolve("api/Orders/").resolve(Constants.GROUP_METABASE),
                JsonUtils.toJsonString(cyclic).getBytes(StandardCharsets.UTF_8));
        assertThrows(InvalidArgumentException.class, service::refreshLocal);
        assertEquals("0", service.getGroup("g1").getParentId());
        assertEquals("return 1;", service.file("a").getScript());
        assertEquals("orders", service.getGroupPath("g1"));
    }

    @Test
    void reloadWaitIsOutsideResourceLockAndLocalPublicationHappensOnce() {
        WorkspaceResourceCoordinator coordinator = new WorkspaceResourceCoordinator();
        AtomicInteger reloads = new AtomicInteger();
        AtomicInteger clears = new AtomicInteger();
        FileResource resource = new FileResource(directory.toFile(), false, directory.toString()) {
            @Override public WorkspaceResourceCoordinator getWorkspaceCoordinator() { return coordinator; }
            @Override public void reload(Runnable localRefresh) {
                coordinator.assertCanWait();
                reloads.incrementAndGet();
                coordinator.write(() -> { coordinator.refreshLocal(); return null; });
            }
        };
        DefaultMagicResourceService service = new DefaultMagicResourceService(resource,
                Collections.singletonList(new ApiInfoMagicResourceStorage("")), event -> {
            if (event instanceof MagicEvent && ((MagicEvent) event).getAction() == EventAction.CLEAR) {
                clears.incrementAndGet();
            }
        });
        service.refresh();
        assertEquals(1, reloads.get());
        assertEquals(1, clears.get());
        assertThrows(IllegalStateException.class, () -> coordinator.write(() -> { service.refresh(); return null; }));
    }

    @Test
    void fixedRootMetadataUsesStorageRootAndSurvivesReload() throws Exception {
        ApiInfoMagicResourceStorage storage = rootStorage();
        AtomicInteger groupEvents = new AtomicInteger();
        DefaultMagicResourceService service = new DefaultMagicResourceService(
                new FileResource(directory.toFile(), false, directory.toString()),
                Collections.singletonList(storage), event -> {
                    if (event instanceof GroupEvent) groupEvents.incrementAndGet();
                });
        service.refreshLocal();
        assertTrue(service.allowRead("full"), "未持久化的虚拟根不参与全量授权");
        Group root = service.getGroup("rooted:0").copy();
        root.setProperties(Collections.singletonMap("sourceRevision", "revision-one"));
        assertTrue(service.saveGroup(root));
        Path metadata = directory.resolve("rooted/group.json");
        assertTrue(Files.isRegularFile(metadata));
        assertFalse(Files.exists(directory.resolve("rooted/src")));
        assertEquals("rooted", service.getGroupResource(root.getId()).name());
        assertEquals(0, groupEvents.get(), "内部根元数据不生成用户分组历史");
        assertFalse(service.allowRead("full"), "已持久化的根元数据必须进入全量授权");
        assertTrue(service.getWorkspaceCoordinator().write(() -> service.getWorkspaceCoordinator()
                .getResourcePaths().stream().anyMatch(path -> path.replace('\\', '/').endsWith("rooted/group.json"))));
        service.refreshLocal();
        assertEquals("revision-one", service.getGroup(root.getId()).getAttribute("sourceRevision"));
        byte[] before = Files.readAllBytes(metadata);
        Group moved = service.getGroup(root.getId()).copy();
        moved.setParentId("some-other-group");
        assertThrows(InvalidArgumentException.class, () -> service.saveGroup(moved));
        assertArrayEquals(before, Files.readAllBytes(metadata));
        assertEquals("0", service.getGroup(root.getId()).getParentId());
    }

    @Test
    void fixedRootTransactionFailureKeepsCacheAndFreezesFurtherWrites() {
        DefaultMagicResourceService service = new DefaultMagicResourceService(
                new FileResource(directory.toFile(), false, directory.toString()),
                Collections.singletonList(rootStorage()), event -> { });
        service.refreshLocal();
        Group root = service.getGroup("rooted:0").copy();
        root.setProperties(Collections.singletonMap("sourceRevision", "revision-one"));
        assertTrue(service.saveGroup(root));
        Group changed = service.getGroup(root.getId()).copy();
        changed.setProperties(Collections.singletonMap("sourceRevision", "revision-two"));
        assertThrows(IllegalStateException.class, () -> service.writeTransaction(() -> {
            assertTrue(service.saveGroup(changed));
            throw new IllegalStateException("根元数据之后的事务步骤失败");
        }));
        assertEquals("revision-one", service.getGroup(root.getId()).getAttribute("sourceRevision"));
        assertTrue(service.getWorkspaceCoordinator().isInconsistent());
        assertThrows(InvalidArgumentException.class, () -> service.saveGroup(changed));
    }

    private ApiInfoMagicResourceStorage rootStorage() {
        return new ApiInfoMagicResourceStorage("") {
            @Override public String folder() { return "rooted"; }
            @Override public boolean allowRoot() { return true; }
            @Override public boolean requirePath() { return false; }
            @Override public boolean allowRead() { return false; }
        };
    }
}
