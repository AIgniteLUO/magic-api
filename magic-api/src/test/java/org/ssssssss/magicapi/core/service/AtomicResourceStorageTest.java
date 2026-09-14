package org.ssssssss.magicapi.core.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.ssssssss.magicapi.core.event.FileEvent;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.resource.FileResource;
import org.ssssssss.magicapi.core.resource.Resource;
import org.ssssssss.magicapi.core.service.impl.ApiInfoMagicResourceStorage;
import org.ssssssss.magicapi.core.service.impl.DefaultMagicResourceService;
import org.ssssssss.magicapi.core.servlet.MagicRequestContextHolder;
import org.ssssssss.magicapi.utils.WebUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Timeout(60)
class AtomicResourceStorageTest {
    @TempDir Path directory;
    MagicRequestContextHolder previous;

    @BeforeEach void setup() {
        previous = WebUtils.magicRequestContextHolder;
        WebUtils.magicRequestContextHolder = mock(MagicRequestContextHolder.class);
    }
    @AfterEach void restore() { WebUtils.magicRequestContextHolder = previous; }

    @Test
    void saveMoveAndLockUseStoragePersistenceHook() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        ApiInfoMagicResourceStorage storage = atomicStorage(writes);
        DefaultMagicResourceService service = service(storage, new AtomicBoolean());
        assertTrue(service.saveGroup(ResourceRenameTest.group("g1", "Orders", "orders")));
        assertTrue(service.saveGroup(ResourceRenameTest.group("g2", "Archive", "archive")));
        assertTrue(service.saveFile(ResourceRenameTest.api("a", "First", "first", "return 1;")));
        assertTrue(service.lock("a"));
        assertTrue(service.unlock("a"));
        assertTrue(service.move("a", "g2"));
        assertEquals(4, writes.get());
        assertFalse(Files.exists(directory.resolve("api/Orders/First.ms")));
        assertEquals("return 1;", storage.read(Files.readAllBytes(directory.resolve("api/Archive/First.ms"))).getScript());
        service.refreshLocal();
        assertEquals("g2", service.file("a").getGroupId());
    }

    @Test
    void publicationFailureAfterAtomicCommitKeepsCompleteDiskAndFreezesFurtherWrites() throws Exception {
        AtomicBoolean failPublication = new AtomicBoolean();
        ApiInfoMagicResourceStorage storage = atomicStorage(new AtomicInteger());
        DefaultMagicResourceService service = service(storage, failPublication);
        assertTrue(service.saveGroup(ResourceRenameTest.group("g1", "Orders", "orders")));
        assertTrue(service.saveFile(ResourceRenameTest.api("a", "First", "first", "old")));
        ApiInfo changed = ((ApiInfo) service.file("a")).copy();
        changed.setScript("complete new revision");
        failPublication.set(true);
        assertThrows(IllegalStateException.class, () -> service.saveFile(changed));
        assertEquals("old", service.file("a").getScript(), "保留最后一致缓存，不能宣称磁盘也已回滚");
        assertEquals("complete new revision", storage.read(Files.readAllBytes(directory.resolve("api/Orders/First.ms"))).getScript());
        assertTrue(service.getWorkspaceCoordinator().isInconsistent());
        assertThrows(RuntimeException.class, () -> service.saveFile(changed));
    }

    private ApiInfoMagicResourceStorage atomicStorage(AtomicInteger writes) {
        return new ApiInfoMagicResourceStorage("") {
            @Override public boolean writeResource(Resource resource, MagicEntity entity) {
                writes.incrementAndGet();
                return resource.writeAtomic(write(entity));
            }
        };
    }

    private DefaultMagicResourceService service(ApiInfoMagicResourceStorage storage, AtomicBoolean fail) {
        DefaultMagicResourceService service = new DefaultMagicResourceService(
                new FileResource(directory.toFile(), false, directory.toString()), Collections.singletonList(storage), event -> {
            if (fail.get() && event instanceof FileEvent) throw new IllegalStateException("模拟发布失败");
        });
        service.refreshLocal();
        return service;
    }
}
