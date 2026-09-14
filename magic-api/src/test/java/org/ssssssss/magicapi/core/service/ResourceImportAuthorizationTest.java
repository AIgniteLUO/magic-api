package org.ssssssss.magicapi.core.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.ssssssss.magicapi.core.config.Constants;
import org.ssssssss.magicapi.core.model.ApiInfo;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.resource.FileResource;
import org.ssssssss.magicapi.core.service.impl.ApiInfoMagicResourceStorage;
import org.ssssssss.magicapi.core.service.impl.DefaultMagicResourceService;
import org.ssssssss.magicapi.core.servlet.MagicRequestContextHolder;
import org.ssssssss.magicapi.utils.JsonUtils;
import org.ssssssss.magicapi.utils.WebUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Timeout(60)
class ResourceImportAuthorizationTest {
    @TempDir Path directory;
    private MagicRequestContextHolder previous;

    @BeforeEach void setup() {
        previous = WebUtils.magicRequestContextHolder;
        WebUtils.magicRequestContextHolder = mock(MagicRequestContextHolder.class);
    }

    @AfterEach void restore() { WebUtils.magicRequestContextHolder = previous; }

    @Test void emptyProtectedTypeDoesNotBlockOrdinaryFullRestore() throws Exception {
        ScopedStorage protectedStorage = new ScopedStorage("protected", 0, new ArrayList<>());
        DefaultMagicResourceService service = service(new ApiInfoMagicResourceStorage(""), protectedStorage);
        Group group = ResourceRenameTest.group("g1", "Orders", "orders");
        ApiInfo api = ResourceRenameTest.api("a", "First", "first", "restored");
        assertTrue(service.upload(new ByteArrayInputStream(apiZip(group, api)), true));
        assertEquals("restored", service.file("a").getScript());
        assertEquals(0, protectedStorage.opened.get(), "虚拟空根不属于恢复影响范围");
        assertFalse(service.getWorkspaceCoordinator().isInconsistent());
    }

    @Test void fullRestoreChecksProtectedResourcesBeforeDeletingAnything() throws Exception {
        ScopedStorage storage = new ScopedStorage("protected", 0, new ArrayList<>());
        DefaultMagicResourceService service = service(new ApiInfoMagicResourceStorage(""), storage);
        storage.writable = true;
        assertTrue(service.saveFile(entity("protected", "secret", "original")));
        storage.writable = false;
        Group group = ResourceRenameTest.group("g1", "Orders", "orders");
        ApiInfo api = ResourceRenameTest.api("a", "First", "first", "restored");
        assertThrows(SecurityException.class, () -> service.upload(new ByteArrayInputStream(apiZip(group, api)), true));
        assertEquals("original", service.file("secret").getScript());
        assertTrue(Files.exists(directory.resolve("protected/secret.ms")));
        assertNull(service.file("a"));
        assertFalse(service.getWorkspaceCoordinator().isInconsistent());
    }

    @Test void scopedRestoreValidatesWholeBatchAndWritesReferencesLast() throws Exception {
        List<String> written = new ArrayList<>();
        ScopedStorage head = new ScopedStorage("head", 20, written);
        ScopedStorage content = new ScopedStorage("content", 10, written);
        head.allowRestore = true;
        content.allowRestore = true;
        DefaultMagicResourceService service = service(head, content);
        byte[] zip = sourceZip(head, entity("head", "h", "c"), content, entity("content", "c", "中文\r\n原内容"));
        assertTrue(service.upload(new ByteArrayInputStream(zip), true));
        assertEquals(Arrays.asList("content", "head"), written);
        assertEquals(2, head.candidates);
        assertEquals(2, content.candidates);
        assertFalse(head.writable);
        assertFalse(content.writable);
        assertThrows(SecurityException.class, () -> service.saveFile(entity("head", "h", "forged")));
        service.refreshLocal();
        assertEquals("中文\r\n原内容", service.file("c").getScript());
    }

    @Test void laterValidationFailureClosesEarlierScopeWithoutMutatingWorkspace() throws Exception {
        ScopedStorage first = new ScopedStorage("a_content", 10, new ArrayList<>());
        ScopedStorage second = new ScopedStorage("z_head", 20, new ArrayList<>());
        first.allowRestore = true;
        DefaultMagicResourceService service = service(first, second);
        byte[] zip = sourceZip(first, entity("a_content", "c", "original"), second, entity("z_head", "h", "c"));
        assertThrows(SecurityException.class, () -> service.upload(new ByteArrayInputStream(zip), true));
        assertEquals(1, first.opened.get());
        assertFalse(first.writable);
        assertNull(service.file("c"));
        assertFalse(service.getWorkspaceCoordinator().isInconsistent());
    }

    private DefaultMagicResourceService service(MagicResourceStorage<?>... storages) {
        DefaultMagicResourceService service = new DefaultMagicResourceService(
                new FileResource(directory.toFile(), false, directory.toString()), Arrays.asList(storages), event -> { });
        service.refreshLocal();
        return service;
    }

    private static ApiInfo entity(String type, String id, String script) {
        ApiInfo entity = ResourceRenameTest.api(id, id, id, script);
        entity.setGroupId(type + ":0");
        return entity;
    }

    private byte[] apiZip(Group group, ApiInfo api) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            add(zip, "api/Orders/" + Constants.GROUP_METABASE, JsonUtils.toJsonString(group).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            add(zip, "api/Orders/First.ms", new ApiInfoMagicResourceStorage("").write(api));
        }
        return bytes.toByteArray();
    }

    private byte[] sourceZip(ScopedStorage first, ApiInfo firstEntity, ScopedStorage second, ApiInfo secondEntity) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            add(zip, first.folder() + "/" + firstEntity.getName() + ".ms", first.write(firstEntity));
            add(zip, second.folder() + "/" + secondEntity.getName() + ".ms", second.write(secondEntity));
        }
        return bytes.toByteArray();
    }

    private static void add(ZipOutputStream zip, String path, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static final class ScopedStorage extends ApiInfoMagicResourceStorage {
        private final String folder;
        private final int order;
        private final List<String> written;
        private final AtomicInteger opened = new AtomicInteger();
        private boolean writable;
        private boolean allowRestore;
        private int candidates;

        ScopedStorage(String folder, int order, List<String> written) {
            super(""); this.folder = folder; this.order = order; this.written = written;
        }
        @Override public String folder() { return folder; }
        @Override public boolean allowRoot() { return true; }
        @Override public boolean requirePath() { return false; }
        @Override public int importOrder() { return order; }
        @Override public String buildMappingKey(ApiInfo entity) { return entity.getId(); }
        @Override public void authorizeWrite() {
            if (!writable) throw new SecurityException("必须经受控导入");
        }
        @Override public MagicResourceImportScope openImport(MagicResourceImportContext context) {
            if (!allowRestore) throw new SecurityException("无恢复授权");
            assertTrue(context.isReplaceWorkspace());
            candidates = context.getEntities().size();
            opened.incrementAndGet();
            writable = true;
            return () -> writable = false;
        }
        @Override public boolean writeResource(org.ssssssss.magicapi.core.resource.Resource resource, MagicEntity entity) {
            written.add(folder);
            return resource.writeAtomic(write(entity));
        }
    }
}
