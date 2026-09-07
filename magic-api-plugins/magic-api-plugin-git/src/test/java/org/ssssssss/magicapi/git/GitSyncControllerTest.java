package org.ssssssss.magicapi.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.method.HandlerMethod;
import org.ssssssss.magicapi.core.config.Constants;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.context.MagicUser;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.exception.MagicLoginException;
import org.ssssssss.magicapi.core.interceptor.Authorization;
import org.ssssssss.magicapi.core.interceptor.AuthorizationInterceptor;
import org.ssssssss.magicapi.core.interceptor.MagicWebRequestInterceptor;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.resource.FileResource;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletResponse;
import org.ssssssss.magicapi.core.web.MagicResourceController;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(60)
class GitSyncControllerTest {
    @TempDir Path directory;

    @Test
    void installingPluginWithoutGitWorkspaceReportsUnsupported() throws Exception {
        MagicConfiguration configuration = new MagicConfiguration();
        configuration.setWorkspace(new FileResource(directory.toFile(), false, directory.toString()));
        configuration.setAuthorizationInterceptor(new AuthorizationInterceptor() { });
        GitSyncSnapshot status = invoke(configuration, "status", request()).getData();
        assertFalse(status.isSupported());
        assertFalse(status.isCanRetry());
        assertFalse(invoke(configuration, "retry", request()).getData().isSupported());
    }

    @Test
    void resourceTreeUsesOneSnapshotAcrossGroupAndFileAuthorization() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            AtomicInteger authorizedFiles = new AtomicInteger();
            MagicConfiguration configuration = config(workspace, new AuthorizationInterceptor() {
                @Override public boolean allowVisit(MagicUser user, MagicHttpServletRequest request, Authorization operation, Group group) {
                    assertThrows(IllegalStateException.class, workspace.repo.getCoordinator()::assertCanWait);
                    return true;
                }
                @Override public boolean allowVisit(MagicUser user, MagicHttpServletRequest request, Authorization operation, MagicEntity entity) {
                    assertThrows(IllegalStateException.class, workspace.repo.getCoordinator()::assertCanWait);
                    authorizedFiles.incrementAndGet();
                    return true;
                }
            });
            configuration.setMagicDynamicRegistries(Collections.singletonList(workspace.registry));
            assertEquals(2, new MagicResourceController(configuration).resources(request())
                    .getData().get("api").getChildren().get(0).getChildren().size());
            assertEquals(2, authorizedFiles.get());
        }
    }

    @Test
    void viewPermissionIsRequiredBeforeReturningStatus() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            MagicConfiguration configuration = config(workspace, new AuthorizationInterceptor() {
                @Override public boolean allowVisit(MagicUser user, MagicHttpServletRequest request, Authorization operation) {
                    return operation != Authorization.VIEW;
                }
            });
            assertEquals(-10, assertThrows(InvalidArgumentException.class,
                    () -> invoke(configuration, "status", request())).getCode());
            assertEquals(0, workspace.repo.fetchCalls.get());
        }
    }

    @Test
    void reloadPermissionControlsRetryAndCanRetryWithoutBorrowingPushPermission() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            MagicConfiguration configuration = config(workspace, new AuthorizationInterceptor() {
                @Override public boolean allowVisit(MagicUser user, MagicHttpServletRequest request, Authorization operation) {
                    return operation != Authorization.RELOAD;
                }
            });
            assertFalse(invoke(configuration, "status", request()).getData().isCanRetry());
            assertEquals(-10, assertThrows(InvalidArgumentException.class,
                    () -> invoke(configuration, "retry", request())).getCode());
            configuration.setAuthorizationInterceptor(new AuthorizationInterceptor() {
                @Override public boolean allowVisit(MagicUser user, MagicHttpServletRequest request, Authorization operation) {
                    return operation != Authorization.PUSH;
                }
            });
            GitWorkspaceFixture.Gate fetch = workspace.repo.blockFetch();
            GitSyncSnapshot queued = invoke(configuration, "retry", request()).getData();
            assertNotNull(queued.getTaskId());
            fetch.awaitEntered();
            assertEquals(queued.getTaskId(), invoke(configuration, "retry", request()).getData().getTaskId());
            fetch.release();
            workspace.service.refresh();
        }
    }

    @Test
    void readonlyWorkspaceCannotRetryEvenWithReloadPermission() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            MagicConfiguration configuration = config(workspace, new AuthorizationInterceptor() { });
            configuration.setWorkspace(new GitResource(true, workspace.localPath.toFile(), workspace.localPath.toString(), workspace.repo));
            assertFalse(invoke(configuration, "status", request()).getData().isCanRetry());
            assertEquals(-2, assertThrows(InvalidArgumentException.class,
                    () -> invoke(configuration, "retry", request())).getCode());
            assertEquals(0, workspace.repo.fetchCalls.get());
        }
    }

    @Test
    void existingLoginInterceptorRejectsUnauthenticatedStatusAndRetry() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            MagicConfiguration configuration = config(workspace, new AuthorizationInterceptor() {
                @Override public boolean requireLogin() { return true; }
                @Override public MagicUser getUserByToken(String token) throws MagicLoginException {
                    if (!"valid-token".equals(token)) { throw new MagicLoginException("请先登录"); }
                    return new MagicUser("user", "测试用户", token);
                }
            });
            assertThrows(MagicLoginException.class, () -> invoke(configuration, "status", request()));
            assertThrows(MagicLoginException.class, () -> invoke(configuration, "retry", request()));
            MagicHttpServletRequest authenticated = request();
            when(authenticated.getHeader(Constants.MAGIC_TOKEN_HEADER)).thenReturn("valid-token");
            assertTrue(invoke(configuration, "status", authenticated).getData().isSupported());
            assertEquals(0, workspace.repo.fetchCalls.get());
        }
    }

    private MagicConfiguration config(GitWorkspaceFixture workspace, AuthorizationInterceptor authorization) {
        MagicConfiguration configuration = new MagicConfiguration();
        configuration.setWorkspace(workspace.resource);
        configuration.setMagicResourceService(workspace.service);
        configuration.setAuthorizationInterceptor(authorization);
        return configuration;
    }

    private MagicHttpServletRequest request() {
        MagicHttpServletRequest request = mock(MagicHttpServletRequest.class);
        Map<String, Object> attributes = new HashMap<>();
        doAnswer(call -> { attributes.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(request).setAttribute(anyString(), any());
        when(request.getAttribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0)));
        return request;
    }

    private org.ssssssss.magicapi.core.model.JsonBean<GitSyncSnapshot> invoke(MagicConfiguration configuration,
            String method, MagicHttpServletRequest request) throws Exception {
        GitSyncController controller = new GitSyncController(configuration);
        MagicWebRequestInterceptor interceptor = new MagicWebRequestInterceptor(null, configuration.getAuthorizationInterceptor()) { };
        interceptor.handle(new HandlerMethod(controller, method, MagicHttpServletRequest.class), request,
                mock(MagicHttpServletResponse.class));
        return "status".equals(method) ? controller.status(request) : controller.retry(request);
    }
}
