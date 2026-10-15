package org.ssssssss.magicapi.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.method.HandlerMethod;
import org.ssssssss.magicapi.core.config.Constants;
import org.ssssssss.magicapi.core.config.JsonCodeConstants;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.context.MagicUser;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.exception.MagicLoginException;
import org.ssssssss.magicapi.core.interceptor.Authorization;
import org.ssssssss.magicapi.core.interceptor.AuthorizationInterceptor;
import org.ssssssss.magicapi.core.interceptor.MagicWebRequestInterceptor;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.core.model.JsonBean;
import org.ssssssss.magicapi.core.model.MagicEntity;
import org.ssssssss.magicapi.core.resource.FileResource;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletResponse;
import org.ssssssss.magicapi.core.web.MagicResourceController;
import org.ssssssss.magicapi.utils.JsonUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(60)
class GitSyncControllerTest {
    private static final String UNKNOWN_COMMIT = "commit:a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

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

    @Test
    void recordsEndpointsRequireViewPermissionAndNeverTouchSynchronization() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.save(workspace.service, "a", "return 11;");
            workspace.service.refresh();
            String head = workspace.headCommit();
            int fetches = workspace.repo.fetchCalls.get();
            int pushes = workspace.repo.pushCalls.get();
            AtomicInteger checked = new AtomicInteger();
            MagicConfiguration denied = config(workspace, new AuthorizationInterceptor() {
                @Override public boolean allowVisit(MagicUser user, MagicHttpServletRequest request, Authorization operation) {
                    checked.incrementAndGet();
                    return operation != Authorization.VIEW;
                }
            });
            assertEquals(-10, assertThrows(InvalidArgumentException.class, () -> records(denied, request())).getCode());
            assertEquals(-10, assertThrows(InvalidArgumentException.class,
                    () -> detail(denied, request("id", "commit:" + head))).getCode());
            assertEquals(-10, assertThrows(InvalidArgumentException.class,
                    () -> diff(denied, request("id", "commit:" + head, "path", "api/Orders/First.ms"))).getCode());
            assertEquals(3, checked.get());
            assertEquals(fetches, workspace.repo.fetchCalls.get());

            MagicConfiguration configuration = config(workspace, new AuthorizationInterceptor() { });
            GitSyncSnapshot before = workspace.repo.snapshot(true);
            assertTrue(records(configuration, request()).getData().isSupported());
            assertEquals("confirmed", detail(configuration, request("id", "commit:" + head)).getData().getOutcome());
            assertTrue(diff(configuration, request("id", "commit:" + head, "path", "api/Orders/First.ms")).getData().isText());
            GitSyncSnapshot after = workspace.repo.snapshot(true);
            assertEquals(before.getStatusVersion(), after.getStatusVersion(), "纯查询不得改变状态版本");
            assertEquals(before.getTaskId(), after.getTaskId(), "纯查询不得调度任务");
            assertEquals(before.getState(), after.getState());
            assertEquals(fetches, workspace.repo.fetchCalls.get(), "纯查询不得触发同步");
            assertEquals(pushes, workspace.repo.pushCalls.get());
        }
    }

    @Test
    void recordsParametersAreValidatedAndClamped() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.save(workspace.service, "a", "return 11;");
            workspace.service.refresh();
            String head = workspace.headCommit();
            MagicConfiguration configuration = config(workspace, new AuthorizationInterceptor() { });
            int invalid = JsonCodeConstants.PARAMETER_INVALID.getCode();

            assertEquals(invalid, assertThrows(InvalidArgumentException.class,
                    () -> records(configuration, request("limit", "abc"))).getCode());
            assertEquals(invalid, assertThrows(InvalidArgumentException.class,
                    () -> records(configuration, request("before", "%%%"))).getCode());
            assertEquals(invalid, assertThrows(InvalidArgumentException.class,
                    () -> detail(configuration, request())).getCode());
            assertEquals(invalid, assertThrows(InvalidArgumentException.class,
                    () -> detail(configuration, request("id", "commit:zzz"))).getCode());
            assertEquals(invalid, assertThrows(InvalidArgumentException.class,
                    () -> detail(configuration, request("id", "attempt:x"))).getCode());
            assertEquals(invalid, assertThrows(InvalidArgumentException.class,
                    () -> detail(configuration, request("id", "unknown:" + head))).getCode());
            assertEquals(invalid, assertThrows(InvalidArgumentException.class,
                    () -> diff(configuration, request("id", "commit:" + head))).getCode());
            assertEquals(invalid, assertThrows(InvalidArgumentException.class,
                    () -> diff(configuration, request("id", "attempt:1", "path", "api/Orders/First.ms"))).getCode());

            assertEquals(1, records(configuration, request("limit", "0")).getData().getRecords().size(), "越界钳制到 1");
            assertEquals(1, records(configuration, request("limit", "-5")).getData().getRecords().size());
            assertEquals(2, records(configuration, request("limit", "9999")).getData().getRecords().size(), "越界钳制到 200");
        }
    }

    @Test
    void nonGitWorkspaceReportsUnsupportedRecordsAndRejectsDetails() throws Exception {
        MagicConfiguration configuration = new MagicConfiguration();
        configuration.setWorkspace(new FileResource(directory.toFile(), false, directory.toString()));
        configuration.setAuthorizationInterceptor(new AuthorizationInterceptor() { });
        JsonBean<GitSyncRecords> page = records(configuration, request("limit", "abc"));
        assertFalse(page.getData().isSupported());
        assertTrue(page.getData().getRecords().isEmpty());
        assertFalse(page.getData().isHasMore());
        assertNull(page.getData().getNextBefore());
        assertNull(page.getData().getInstanceId());
        assertEquals(JsonCodeConstants.FILE_NOT_FOUND.getCode(), assertThrows(InvalidArgumentException.class,
                () -> detail(configuration, request("id", UNKNOWN_COMMIT))).getCode());
        assertEquals(JsonCodeConstants.FILE_NOT_FOUND.getCode(), assertThrows(InvalidArgumentException.class,
                () -> diff(configuration, request("id", UNKNOWN_COMMIT, "path", "a.ms"))).getCode());
    }

    @Test
    void readonlyWorkspaceStillServesRecordsWithoutAllowingRetry() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            MagicConfiguration configuration = config(workspace, new AuthorizationInterceptor() { });
            configuration.setWorkspace(new GitResource(true, workspace.localPath.toFile(),
                    workspace.localPath.toString(), workspace.repo));
            assertTrue(records(configuration, request()).getData().isSupported(), "只读不影响查询");
            assertEquals(-2, assertThrows(InvalidArgumentException.class,
                    () -> invoke(configuration, "retry", request())).getCode());
            assertEquals(0, workspace.repo.fetchCalls.get());
        }
    }

    @Test
    void recordPayloadsNeverExposeCredentialsUrlAbsolutePathsOrRawFailures() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.save(workspace.service, "a", "return 11;");
            workspace.service.refresh();
            String head = workspace.headCommit();
            workspace.repo.transportFailure = "Authentication failed https://oauth2:TEST_TOKEN@example.invalid/<img onerror=alert(1)> /absolute/private/path";
            assertThrows(GitSyncException.class, workspace.service::refresh);

            MagicConfiguration configuration = config(workspace, new AuthorizationInterceptor() { });
            String records = JsonUtils.toJsonString(records(configuration, request()).getData());
            String detail = JsonUtils.toJsonString(detail(configuration, request("id", "commit:" + head)).getData());
            String diff = JsonUtils.toJsonString(diff(configuration, request("id", "commit:" + head,
                    "path", "api/Orders/First.ms")).getData());
            for (String payload : Arrays.asList(records, detail, diff)) {
                assertFalse(payload.contains("TEST_TOKEN"), payload);
                assertFalse(payload.contains("oauth2"), payload);
                assertFalse(payload.contains("example.invalid"), payload);
                assertFalse(payload.contains("<img"), payload);
                assertFalse(payload.contains("/absolute"), payload);
                assertFalse(payload.contains(directory.toString()), payload);
                assertFalse(payload.contains(workspace.localPath.toString()), payload);
                assertFalse(payload.contains("remote.git"), payload);
            }
            assertTrue(records.contains("AUTHENTICATION_FAILED"), records);
            assertTrue(detail.contains("api/Orders/First.ms"), detail);
        }
    }

    @Test
    void recordPayloadFieldNamesMatchTheDocumentedContract() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.save(workspace.service, "a", "return 11;");
            workspace.service.refresh();
            String head = workspace.headCommit();
            MagicConfiguration configuration = config(workspace, new AuthorizationInterceptor() { });

            String records = JsonUtils.toJsonString(records(configuration, request("limit", "1")).getData());
            for (String field : Arrays.asList("supported", "instanceId", "records", "hasMore", "nextBefore",
                    "id", "kind", "at", "outcome", "fileCount")) {
                assertTrue(records.contains("\"" + field + "\""), field + " 不在 " + records);
            }
            String commitDetail = JsonUtils.toJsonString(detail(configuration, request("id", "commit:" + head)).getData());
            for (String field : Arrays.asList("kind", "commitId", "at", "message", "messageTruncated", "files",
                    "totalFiles", "truncated", "path", "changeType", "text")) {
                assertTrue(commitDetail.contains("\"" + field + "\""), field + " 不在 " + commitDetail);
            }
            String diff = JsonUtils.toJsonString(diff(configuration, request("id", "commit:" + head,
                    "path", "api/Orders/First.ms")).getData());
            for (String field : Arrays.asList("path", "text", "before", "after", "truncated")) {
                assertTrue(diff.contains("\"" + field + "\""), field + " 不在 " + diff);
            }

            workspace.repo.emptyPushResult = true;
            assertThrows(GitSyncException.class, workspace.service::refresh);
            GitSyncRecord attempt = null;
            for (GitSyncRecord record : records(configuration, request()).getData().getRecords()) {
                if ("attempt".equals(record.getKind())) {
                    attempt = record;
                    assertEquals("failed", record.getOutcome());
                    assertEquals("REMOTE_REJECTED", record.getErrorCode());
                    assertTrue(record.getRetryable());
                }
            }
            assertNotNull(attempt, "失败同步必须留下流水记录");
            String attemptDetail = JsonUtils.toJsonString(detail(configuration, request("id", attempt.getId())).getData());
            for (String field : Arrays.asList("kind", "seq", "startedAt", "endedAt", "outcome", "phase", "errorCode",
                    "retryable", "localGeneration", "confirmedGeneration")) {
                assertTrue(attemptDetail.contains("\"" + field + "\""), field + " 不在 " + attemptDetail);
            }
        }
    }

    private MagicConfiguration config(GitWorkspaceFixture workspace, AuthorizationInterceptor authorization) {
        MagicConfiguration configuration = new MagicConfiguration();
        configuration.setWorkspace(workspace.resource);
        configuration.setMagicResourceService(workspace.service);
        configuration.setAuthorizationInterceptor(authorization);
        return configuration;
    }

    private MagicHttpServletRequest request(String... parameters) {
        MagicHttpServletRequest request = request();
        for (int i = 0; i + 1 < parameters.length; i += 2) {
            when(request.getParameterValues(parameters[i])).thenReturn(new String[] {parameters[i + 1]});
        }
        return request;
    }

    private MagicHttpServletRequest request() {
        MagicHttpServletRequest request = mock(MagicHttpServletRequest.class);
        Map<String, Object> attributes = new HashMap<>();
        doAnswer(call -> { attributes.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(request).setAttribute(anyString(), any());
        when(request.getAttribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0)));
        return request;
    }

    private JsonBean<GitSyncRecords> records(MagicConfiguration configuration, MagicHttpServletRequest request) throws Exception {
        return invokeAs(configuration, "records", request);
    }

    private JsonBean<GitSyncRecordDetail> detail(MagicConfiguration configuration, MagicHttpServletRequest request) throws Exception {
        return invokeAs(configuration, "detail", request);
    }

    private JsonBean<GitSyncDiff> diff(MagicConfiguration configuration, MagicHttpServletRequest request) throws Exception {
        return invokeAs(configuration, "diff", request);
    }

    private JsonBean<GitSyncSnapshot> invoke(MagicConfiguration configuration, String method,
                                             MagicHttpServletRequest request) throws Exception {
        return invokeAs(configuration, method, request);
    }

    @SuppressWarnings("unchecked")
    private <T> JsonBean<T> invokeAs(MagicConfiguration configuration, String method,
                                     MagicHttpServletRequest request) throws Exception {
        GitSyncController controller = new GitSyncController(configuration);
        Method handler = GitSyncController.class.getMethod(method, MagicHttpServletRequest.class);
        MagicWebRequestInterceptor interceptor = new MagicWebRequestInterceptor(null, configuration.getAuthorizationInterceptor()) { };
        interceptor.handle(new HandlerMethod(controller, handler), request, mock(MagicHttpServletResponse.class));
        try {
            return (JsonBean<T>) handler.invoke(controller, request);
        } catch (InvocationTargetException failure) {
            // 反射不能把控制器里的显式拒绝包装成别的异常
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) { throw (Exception) cause; }
            throw failure;
        }
    }
}
