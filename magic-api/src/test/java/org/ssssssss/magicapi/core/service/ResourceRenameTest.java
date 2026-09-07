package org.ssssssss.magicapi.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.ssssssss.magicapi.core.config.Constants;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.context.MagicUser;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.interceptor.Authorization;
import org.ssssssss.magicapi.core.interceptor.AuthorizationInterceptor;
import org.ssssssss.magicapi.core.model.*;
import org.ssssssss.magicapi.core.resource.FileResource;
import org.ssssssss.magicapi.core.service.impl.ApiInfoMagicResourceStorage;
import org.ssssssss.magicapi.core.service.impl.DefaultMagicResourceService;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;
import org.ssssssss.magicapi.core.web.MagicResourceController;
import org.ssssssss.magicapi.utils.WebUtils;
import org.ssssssss.magicapi.core.servlet.MagicRequestContextHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Timeout(60)
class ResourceRenameTest {
    @TempDir Path directory;
    DefaultMagicResourceService service;
    MagicConfiguration configuration;
    MagicResourceController controller;
    MagicHttpServletRequest request;

    MagicRequestContextHolder previousContext;

    @BeforeEach
    void prepare() {
        previousContext = WebUtils.magicRequestContextHolder;
        WebUtils.magicRequestContextHolder = mock(MagicRequestContextHolder.class);
        service = service(false, new ApiInfoMagicResourceStorage(""));
        configuration = new MagicConfiguration();
        configuration.setWorkspace(service.getResource());
        configuration.setMagicResourceService(service);
        configuration.setAuthorizationInterceptor(new AuthorizationInterceptor() { });
        controller = new MagicResourceController(configuration);
        request = mock(MagicHttpServletRequest.class);
        Group group = group("g1", "Orders", "orders");
        assertTrue(service.saveGroup(group));
        assertTrue(service.saveFile(api("a", "First", "first", "return 1;")));
        assertTrue(service.saveFile(api("b", "Second", "second", "return 2;")));
    }

    @AfterEach
    void restoreContext() { WebUtils.magicRequestContextHolder = previousContext; }

    private DefaultMagicResourceService service(boolean readonly, ApiInfoMagicResourceStorage storage) {
        DefaultMagicResourceService resources = new DefaultMagicResourceService(
                new FileResource(directory.toFile(), readonly, directory.toString()),
                Collections.singletonList(storage), event -> { });
        resources.refreshLocal();
        return resources;
    }

    @Test
    void narrowRenamePreservesAllBusinessFieldsAndAuthorizesActualEntity() throws Exception {
        ApiInfo before = service.file("a");
        before = before.copy();
        Parameter parameter = new Parameter();
        parameter.setName("amount");
        before.setParameters(Collections.singletonList(parameter));
        before.setRequestBody("{\"value\":1}");
        before.setResponseBody("{\"result\":true}");
        before.setDescription("业务参数保持");
        before.setScript("var amount = 1;\nreturn amount;");
        assertTrue(service.saveFile(before));
        final ApiInfo baseline = service.file("a");
        AtomicReference<MagicEntity> authorized = new AtomicReference<>();
        configuration.setAuthorizationInterceptor(new AuthorizationInterceptor() {
            @Override
            public boolean allowVisit(MagicUser user, MagicHttpServletRequest request, Authorization operation, MagicEntity entity) {
                assertEquals(Authorization.SAVE, operation);
                assertEquals("a", entity.getId());
                assertEquals("Renamed", entity.getName());
                assertEquals("First", service.file("a").getName());
                authorized.set(entity);
                return true;
            }
        });

        ResourceName response = controller.rename("a", "Renamed", request).getData();
        ApiInfo actual = service.file("a");
        assertEquals("a", response.getId());
        assertEquals("Renamed", response.getName());
        assertSame(authorized.get(), actual);
        assertEquals(baseline.getScript(), actual.getScript());
        assertEquals(baseline.getPath(), actual.getPath());
        assertEquals(baseline.getParameters(), actual.getParameters());
        assertEquals(baseline.getRequestBody(), actual.getRequestBody());
        assertEquals(baseline.getResponseBody(), actual.getResponseBody());
        assertEquals(baseline.getDescription(), actual.getDescription());
        assertEquals(baseline.getCreateTime(), actual.getCreateTime());
        assertEquals("First", baseline.getName());
        assertFalse(Files.exists(directory.resolve("api/Orders/First.ms")));
        assertTrue(Files.exists(directory.resolve("api/Orders/Renamed.ms")));
        service.refreshLocal();
        assertEquals(actual.getScript(), service.file("a").getScript());
        assertEquals("first", ((ApiInfo) service.file("a")).getPath());
    }

    @Test
    void narrowAndFullSaveBothRejectSiblingNameWithoutOverwritingFiles() throws Exception {
        byte[] sibling = Files.readAllBytes(directory.resolve("api/Orders/Second.ms"));
        assertCode(1037, () -> controller.rename("a", "Second", request));
        ApiInfo changed = ((ApiInfo) service.file("a")).copy();
        changed.setName("Second");
        assertCode(1037, () -> service.saveFile(changed));
        ApiInfo imported = api("new-import-id", "Second", "third", "return 3;");
        assertCode(1037, () -> service.saveFile(imported));
        assertArrayEquals(sibling, Files.readAllBytes(directory.resolve("api/Orders/Second.ms")));
        assertEquals("First", service.file("a").getName());
    }

    @Test
    void groupRenameKeepsRoutesAndRefreshesPhysicalMappings() {
        Group before = service.getGroup("g1").copy();
        ResourceName result = controller.rename("g1", "Purchases", request).getData();
        assertEquals("g1", result.getId());
        assertEquals("orders", service.getGroupPath("g1"));
        assertEquals(before.getCreateTime(), service.getGroup("g1").getCreateTime());
        assertEquals("return 1;", service.file("a").getScript());
        ApiInfo updated = ((ApiInfo) service.file("a")).copy();
        updated.setScript("return 10;");
        assertTrue(service.saveFile(updated));
        assertTrue(Files.exists(directory.resolve("api/Purchases/First.ms")));
        assertFalse(Files.exists(directory.resolve("api/Orders")));
    }

    @Test
    void groupRenameUsesObjectAuthorizationEvenWhenGlobalSaveIsAllowed() {
        configuration.setAuthorizationInterceptor(new AuthorizationInterceptor() {
            @Override
            public boolean allowVisit(MagicUser user, MagicHttpServletRequest request, Authorization operation, Group group) {
                assertEquals("g1", group.getId());
                assertEquals("orders", group.getPath());
                return false;
            }
        });
        assertCode(-10, () -> controller.rename("g1", "Denied", request));
        assertEquals("Orders", service.getGroup("g1").getName());
    }

    @Test
    void fileRenameUsesObjectAuthorizationEvenWhenGlobalSaveIsAllowed() {
        configuration.setAuthorizationInterceptor(new AuthorizationInterceptor() {
            @Override
            public boolean allowVisit(MagicUser user, MagicHttpServletRequest request, Authorization operation, MagicEntity entity) {
                assertEquals("a", entity.getId());
                assertEquals("return 1;", entity.getScript());
                return false;
            }
        });
        assertCode(-10, () -> controller.rename("a", "Denied", request));
        assertEquals("First", service.file("a").getName());
    }

    @Test
    void lockedResourceReadonlyWorkspaceAndMissingResourceAreRejected() {
        assertTrue(service.lock("a"));
        assertCode(1008, () -> controller.rename("a", "Locked", request));
        DefaultMagicResourceService readonly = service(true, new ApiInfoMagicResourceStorage(""));
        assertCode(-2, () -> readonly.rename("b", "Readonly", group -> true, entity -> true));
        assertCode(1007, () -> controller.rename("absent", "Missing", request));
    }

    @Test
    void invalidNamesAndGroupCollisionsShareValidation() {
        assertCode(1018, () -> controller.rename("a", " ", request));
        assertCode(1025, () -> controller.rename("a", "../outside", request));
        assertCode(1025, () -> controller.rename("g1", "../outside", request));
        assertCode(1025, () -> service.saveGroup(group("bad", "../outside", "valid")));
        assertTrue(service.saveGroup(group("g2", "Existing", "other")));
        assertCode(1037, () -> controller.rename("g1", "Existing", request));
        assertEquals("Orders", service.getGroup("g1").getName());
    }

    @Test
    void legacyGroupSaveRejectsOccupiedDestinationWhenOnlyParentChanges() throws Exception {
        assertTrue(service.saveGroup(group("parent", "Parent", "parent")));
        Group sibling = group("sibling", "Orders", "other");
        sibling.setParentId("parent");
        assertTrue(service.saveGroup(sibling));
        Path occupied = directory.resolve("api/Parent/Orders/").resolve(Constants.GROUP_METABASE);
        byte[] before = Files.readAllBytes(occupied);
        Group moved = service.getGroup("g1").copy();
        moved.setParentId("parent");
        assertCode(1037, () -> service.saveGroup(moved));
        assertArrayEquals(before, Files.readAllBytes(occupied));
        assertEquals("0", service.getGroup("g1").getParentId());
        assertEquals("sibling", service.getGroup("sibling").getId());
        assertTrue(Files.exists(directory.resolve("api/Orders/First.ms")));
    }

    @Test
    void legacyGroupSaveMovesWholeDirectoryWhenOnlyParentChanges() {
        assertTrue(service.saveGroup(group("parent", "Parent", "parent")));
        Group moved = service.getGroup("g1").copy();
        moved.setParentId("parent");
        assertTrue(service.saveGroup(moved));
        assertEquals("parent/orders", service.getGroupPath("g1"));
        assertTrue(Files.exists(directory.resolve("api/Parent/Orders/First.ms")));
        assertFalse(Files.exists(directory.resolve("api/Orders")));
        service.refreshLocal();
        assertEquals("return 1;", service.file("a").getScript());
        Group cyclic = service.getGroup("parent").copy();
        cyclic.setParentId("g1");
        assertCode(1011, () -> service.saveGroup(cyclic));
    }

    @Test
    void customStorageMustExplicitlySupportNarrowRename() {
        DefaultMagicResourceService unsupported = service(false, new ApiInfoMagicResourceStorage("") {
            @Override public boolean supportsRename() { return false; }
        });
        assertCode(1038, () -> unsupported.rename("a", "Unsupported", group -> true, entity -> true));
        assertEquals("First", unsupported.file("a").getName());
    }

    @Test
    void nestedGroupMoveAndRenameRetainPathsAndCanSaveAfterRefresh() {
        Group nested = group("nested", "Nested", "nested");
        nested.setParentId("g1");
        assertTrue(service.saveGroup(nested));
        ApiInfo child = api("child", "Child", "child", "return 4;");
        child.setGroupId("nested");
        assertTrue(service.saveFile(child));
        service.refreshLocal();
        assertEquals("orders/nested", service.getGroupPath("nested"));
        controller.rename("g1", "RenamedGroup", request);
        assertEquals("orders/nested", service.getGroupPath("nested"));
        assertTrue(service.move("nested", "0"));
        assertEquals("nested", service.getGroupPath("nested"));
        ApiInfo updated = ((ApiInfo) service.file("child")).copy();
        updated.setScript("return 40;");
        assertTrue(service.saveFile(updated));
        service.refreshLocal();
        assertEquals("return 40;", service.file("child").getScript());
        assertTrue(Files.exists(directory.resolve("api/Nested/Child.ms")));
    }

    static Group group(String id, String name, String path) {
        Group group = new Group(id, name);
        group.setParentId("0");
        group.setType("api");
        group.setPath(path);
        return group;
    }

    static ApiInfo api(String id, String name, String path, String script) {
        ApiInfo api = new ApiInfo();
        api.setId(id);
        api.setName(name);
        api.setGroupId("g1");
        api.setPath(path);
        api.setScript(script);
        return api;
    }

    static void assertCode(int code, Runnable action) {
        assertEquals(code, assertThrows(InvalidArgumentException.class, action::run).getCode());
    }
}
