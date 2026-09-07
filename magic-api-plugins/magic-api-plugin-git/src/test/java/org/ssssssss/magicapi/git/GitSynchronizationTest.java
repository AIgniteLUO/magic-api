package org.ssssssss.magicapi.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.dircache.DirCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.config.Constants;
import org.ssssssss.magicapi.core.event.EventAction;
import org.ssssssss.magicapi.core.model.MagicNotify;
import org.ssssssss.magicapi.core.model.TreeNode;
import org.ssssssss.magicapi.core.model.Group;
import org.ssssssss.magicapi.utils.JsonUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.ssssssss.magicapi.git.GitWorkspaceFixture.api;

@Timeout(60)
class GitSynchronizationTest {
    @TempDir Path directory;

    @Test
    void fetchDoesNotBlockSavesOrTreeReadsAndMergesBothCheckouts() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            GitWorkspaceFixture.Gate fetch = workspace.repo.blockFetch();
            workspace.save(workspace.service, "a", "return 11;");
            Future<?> reload = workspace.reloadAsync();
            fetch.awaitEntered();
            long generation = workspace.repo.snapshot(true).getLocalGeneration();
            workspace.save(workspace.otherService, "b", "return 22;");
            workspace.commitOther();
            Future<Boolean> saved = workspace.clients.submit(() -> workspace.service.saveFile(api("c", "Third", "third", "return 33;")));
            Future<Map<String, TreeNode<Group>>> tree = workspace.clients.submit(() -> { return workspace.service.tree(); });
            assertTrue(saved.get(5, TimeUnit.SECONDS));
            assertNotNull(tree.get(5, TimeUnit.SECONDS).get("api"));
            assertEquals(1, fetch.released.getCount(), "保存/读取必须在解除网络等待前结束");
            assertTrue(workspace.repo.snapshot(true).getLocalGeneration() > generation);
            fetch.release();
            reload.get(15, TimeUnit.SECONDS);
            assertEquals("return 11;", workspace.remoteFile("First").getScript());
            assertEquals("return 22;", workspace.remoteFile("Second").getScript());
            assertEquals("return 33;", workspace.remoteFile("Third").getScript());
            assertEquals("return 22;", workspace.service.file("b").getScript());
            assertEquals("return 22;", workspace.registry.getMapping("GET:/orders/second").getScript());
            assertTrue(workspace.routes.getHandlerMethods().containsKey(workspace.routes.paths("/orders/second")
                    .methods(org.springframework.web.bind.annotation.RequestMethod.GET).build()));
            assertEquals("idle", workspace.repo.snapshot(true).getState());
            assertEquals(1, workspace.clears.get());
        }
    }

    @Test
    void pushConfirmsOnlyCapturedGenerationAndRetryRequestsShareOneWorker() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            GitWorkspaceFixture.Gate push = workspace.repo.blockPush();
            workspace.save(workspace.service, "a", "return 10;");
            Future<?> first = workspace.reloadAsync();
            push.awaitEntered();
            GitSyncSnapshot inFlight = workspace.repo.snapshot(true);
            workspace.clients.submit(() -> workspace.save(workspace.service, "b", "return 20;")).get(5, TimeUnit.SECONDS);
            workspace.clients.submit(() -> { return workspace.service.tree(); }).get(5, TimeUnit.SECONDS);
            assertEquals(1, push.released.getCount());
            for (int i = 0; i < 30; i++) {
                assertEquals(inFlight.getTaskId(), workspace.repo.retry(true).getTaskId());
            }
            assertEquals(1, workspace.repo.fetchCalls.get());
            assertEquals(1, workspace.repo.pushCalls.get());
            GitWorkspaceFixture.Gate nextFetch = workspace.repo.blockFetch();
            push.release();
            first.get(15, TimeUnit.SECONDS);
            nextFetch.awaitEntered();
            GitSyncSnapshot next = workspace.repo.snapshot(true);
            assertTrue(next.getLocalGeneration() > next.getConfirmedGeneration());
            assertEquals("return 2;", workspace.remoteFile("Second").getScript());
            Future<?> second = workspace.reloadAsync();
            nextFetch.release();
            second.get(15, TimeUnit.SECONDS);
            assertEquals("return 20;", workspace.remoteFile("Second").getScript());
            assertEquals(workspace.repo.snapshot(true).getLocalGeneration(), workspace.repo.snapshot(true).getConfirmedGeneration());
        }
    }

    @Test
    void nonFastForwardPushResultIsFailureAndNewSaveDoesNotClearIt() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            GitWorkspaceFixture.Gate push = workspace.repo.blockPush();
            workspace.save(workspace.service, "a", "return 10;");
            Future<?> first = workspace.reloadAsync();
            push.awaitEntered();
            workspace.save(workspace.otherService, "b", "return 20;");
            workspace.commitOther();
            push.release();
            assertFailure(first, GitSyncError.REMOTE_REJECTED);
            GitSyncSnapshot failed = workspace.repo.snapshot(true);
            assertEquals("failed", failed.getState());
            assertEquals("push", failed.getPhase());
            assertTrue(failed.isCanRetry());
            assertEquals(0, failed.getConfirmedGeneration());
            workspace.save(workspace.service, "a", "return 100;");
            assertEquals("REMOTE_REJECTED", workspace.repo.snapshot(true).getErrorCode());
            assertEquals("failed", workspace.repo.snapshot(true).getState());
            assertTrue(workspace.repo.snapshot(true).getLocalGeneration() > failed.getLocalGeneration());
            assertEquals("return 100;", workspace.service.file("a").getScript());
            GitWorkspaceFixture.Gate fetch = workspace.repo.blockFetch();
            GitSyncSnapshot retry = workspace.repo.retry(true);
            fetch.awaitEntered();
            for (int i = 0; i < 20; i++) { assertEquals(retry.getTaskId(), workspace.repo.retry(true).getTaskId()); }
            Future<?> reloaded = workspace.reloadAsync();
            fetch.release();
            reloaded.get(15, TimeUnit.SECONDS);
            assertEquals("return 100;", workspace.remoteFile("First").getScript());
            assertEquals("return 20;", workspace.remoteFile("Second").getScript());
            assertEquals("idle", workspace.repo.snapshot(true).getState());
        }
    }

    @Test
    void conflictsNeverWriteMarkersOrResetLocallySavedContent() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            GitWorkspaceFixture.Gate fetch = workspace.repo.blockFetch();
            workspace.save(workspace.service, "a", "return 'local';");
            Future<?> first = workspace.reloadAsync();
            fetch.awaitEntered();
            workspace.save(workspace.otherService, "a", "return 'remote';");
            workspace.commitOther();
            fetch.release();
            assertFailure(first, GitSyncError.MERGE_CONFLICT);
            String local = new String(Files.readAllBytes(workspace.localPath.resolve("api/Orders/First.ms")), StandardCharsets.UTF_8);
            assertTrue(local.endsWith("return 'local';"));
            assertFalse(local.contains("<<<<<<<"));
            assertFalse(Files.exists(workspace.localPath.resolve(".git/MERGE_HEAD")));
            try (Git git = Git.open(workspace.localPath.toFile())) { assertTrue(git.status().call().isClean()); }
            assertEquals(0, workspace.repo.pushCalls.get());
            assertFalse(workspace.repo.snapshot(true).isCanRetry());
            workspace.save(workspace.service, "b", "return 'later';");
            assertEquals("return 'later';", workspace.service.file("b").getScript());
            assertEquals("MERGE_CONFLICT", workspace.repo.snapshot(true).getErrorCode());
        }
    }

    @Test
    void interruptedApplyFreezesWritesKeepsLastCacheAndSurvivesRestart() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.save(workspace.otherService, "b", "return 200;");
            workspace.commitOther();
            workspace.repo.failApply = true;
            assertThrows(GitSyncException.class, workspace.service::refresh);
            assertEquals("WORKSPACE_INCONSISTENT", workspace.repo.snapshot(true).getErrorCode());
            assertFalse(workspace.repo.snapshot(true).isCanRetry());
            assertEquals("return 2;", workspace.service.file("b").getScript());
            assertEquals(0, workspace.repo.pushCalls.get());
            assertTrue(Files.exists(workspace.localPath.resolve(".git/magic-api-workspace-inconsistent")));
            assertFrozen(() -> workspace.service.saveFile(api("c", "Third", "third", "return 3;")));
            assertFrozen(() -> workspace.service.move("a", "g"));
            assertFrozen(() -> workspace.service.delete("a"));
            assertFrozen(() -> workspace.resource.getResource("later.txt").write("unsafe"));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            new ZipOutputStream(bytes).close();
            assertEquals(1039, assertThrows(InvalidArgumentException.class,
                    () -> workspace.service.upload(new ByteArrayInputStream(bytes.toByteArray()), false)).getCode());
            assertEquals("WORKSPACE_INCONSISTENT", workspace.repo.retry(true).getErrorCode());
            assertThrows(GitSyncException.class, workspace.repo::verifyWorkspaceRecovery);
            workspace.repo.close();
            try (GitRepo restarted = new GitRepo(workspace.localPath.toString(), workspace.properties)) {
                restarted.setupRepo();
                assertEquals("WORKSPACE_INCONSISTENT", restarted.snapshot(true).getErrorCode());
                assertTrue(restarted.getCoordinator().isInconsistent());
            }
        }
    }

    @Test
    void explicitRecoveryVerifiesCleanTreeBeforeUnfreezingAndDoesNotResetAnything() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.save(workspace.otherService, "b", "return 200;");
            workspace.commitOther();
            workspace.repo.failApply = true;
            assertThrows(GitSyncException.class, workspace.service::refresh);
            assertThrows(GitSyncException.class, workspace.repo::verifyWorkspaceRecovery);
            // 仅对测试临时仓库模拟管理员修复；产品恢复入口自身不做任何 reset。
            try (Git repair = Git.open(workspace.localPath.toFile())) {
                repair.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call();
            }
            workspace.repo.verifyWorkspaceRecovery();
            assertFalse(workspace.repo.getCoordinator().isInconsistent());
            assertFalse(Files.exists(workspace.localPath.resolve(".git/magic-api-workspace-inconsistent")));
            assertEquals("pending", workspace.repo.snapshot(true).getState());
            assertNull(workspace.repo.snapshot(true).getLastSuccessAt());
            workspace.save(workspace.service, "a", "return 99;");
            assertEquals("return 99;", workspace.service.file("a").getScript());
        }
    }

    @Test
    void recoveryRejectsAssumeValidWorktreeBeforePublishingHiddenChanges() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.save(workspace.otherService, "b", "return 200;");
            workspace.commitOther();
            workspace.repo.failApply = true;
            assertThrows(GitSyncException.class, workspace.service::refresh);
            try (Git repair = Git.open(workspace.localPath.toFile())) {
                repair.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call();
                DirCache index = repair.getRepository().lockDirCache();
                try {
                    index.getEntry("api/Orders/First.ms").setAssumeValid(true);
                    index.write();
                    assertTrue(index.commit());
                } finally {
                    index.unlock();
                }
            }
            Path hidden = workspace.localPath.resolve("api/Orders/First.ms");
            String content = new String(Files.readAllBytes(hidden), StandardCharsets.UTF_8);
            Files.write(hidden, content.replace("return 1;", "return 99;").getBytes(StandardCharsets.UTF_8));
            assertThrows(GitSyncException.class, workspace.repo::verifyWorkspaceRecovery);
            assertTrue(workspace.repo.getCoordinator().isInconsistent());
            assertTrue(Files.exists(workspace.localPath.resolve(".git/magic-api-workspace-inconsistent")));
            assertEquals("return 1;", workspace.service.file("a").getScript());
        }
    }

    @Test
    void restartNeverClaimsConfirmationFromTrackingRefAndFindsDirtyFiles() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            assertEquals("unknown", workspace.repo.snapshot(true).getState());
            long version = workspace.repo.snapshot(true).getStatusVersion();
            workspace.repo.snapshot(true);
            assertEquals(version, workspace.repo.snapshot(true).getStatusVersion());
            assertEquals(0, workspace.repo.fetchCalls.get());
            workspace.service.refresh();
            assertEquals("idle", workspace.repo.snapshot(true).getState());
            String instance = workspace.repo.snapshot(true).getInstanceId();
            workspace.repo.close();
            try (GitRepo clean = new GitRepo(workspace.localPath.toString(), workspace.properties)) {
                clean.setupRepo();
                assertEquals("unknown", clean.snapshot(true).getState());
                assertNull(clean.snapshot(true).getLastSuccessAt());
                assertNotEquals(instance, clean.snapshot(true).getInstanceId());
            }
            Files.write(workspace.localPath.resolve("unconfirmed.txt"), "保留本地写入".getBytes(StandardCharsets.UTF_8));
            try (GitRepo dirty = new GitRepo(workspace.localPath.toString(), workspace.properties)) {
                dirty.setupRepo();
                assertEquals("pending", dirty.snapshot(true).getState());
                assertEquals(1, dirty.snapshot(true).getLocalGeneration());
                assertEquals(0, dirty.snapshot(true).getConfirmedGeneration());
            }
        }
    }

    @Test
    void emptyPushResultsCannotDeclareSuccessAndSafeErrorsContainNoSecrets() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.repo.emptyPushResult = true;
            assertThrows(GitSyncException.class, workspace.service::refresh);
            assertEquals("REMOTE_REJECTED", workspace.repo.snapshot(true).getErrorCode());
            assertNull(workspace.repo.snapshot(true).getLastSuccessAt());
            workspace.repo.emptyPushResult = false;
            workspace.repo.transportFailure = "Authentication failed https://oauth2:TEST_TOKEN@example.invalid/<img onerror=alert(1)> /absolute/private/path";
            assertThrows(GitSyncException.class, workspace.service::refresh);
            String snapshot = JsonUtils.toJsonString(workspace.repo.snapshot(true));
            assertTrue(snapshot.contains("AUTHENTICATION_FAILED"));
            assertFalse(snapshot.contains("TEST_TOKEN"));
            assertFalse(snapshot.contains("<img"));
            assertFalse(snapshot.contains("example.invalid"));
            assertFalse(snapshot.contains("/absolute"));
            assertEquals("Git 资源工作区", workspace.resource.toString());
        }
    }

    @Test
    void everyPushDestinationMustConfirmTheConfiguredRef() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.repo.incompletePushResult = true;
            workspace.save(workspace.service, "a", "return 12;");
            assertThrows(GitSyncException.class, workspace.service::refresh);
            assertEquals("REMOTE_REJECTED", workspace.repo.snapshot(true).getErrorCode());
            assertEquals(0, workspace.repo.snapshot(true).getConfirmedGeneration());
            assertNull(workspace.repo.snapshot(true).getLastSuccessAt());
            assertEquals("return 12;", workspace.remoteFile("First").getScript());
        }
    }

    @Test
    void fullUploadPreservesGitDirectoryAndStillSynchronizes() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            workspace.service.export(null, null, archive);
            assertTrue(workspace.service.upload(new ByteArrayInputStream(archive.toByteArray()), true));
            assertTrue(Files.isDirectory(workspace.localPath.resolve(".git")));
            workspace.service.refresh();
            assertEquals("return 1;", workspace.remoteFile("First").getScript());
            assertEquals("idle", workspace.repo.snapshot(true).getState());
        }
    }

    @Test
    void cacheReloadFailureAfterApplyingRemoteKeepsLastKnownDetailsAndFreezes() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            Files.write(workspace.otherPath.resolve("api/Orders/First.ms"), "{}".getBytes(StandardCharsets.UTF_8));
            workspace.commitOther();
            assertThrows(GitSyncException.class, workspace.service::refresh);
            assertEquals("WORKSPACE_INCONSISTENT", workspace.repo.snapshot(true).getErrorCode());
            assertEquals("return 1;", workspace.service.file("a").getScript());
            assertEquals("return 2;", workspace.service.file("b").getScript());
            assertFalse(workspace.repo.snapshot(true).isCanRetry());
            assertEquals(0, workspace.repo.pushCalls.get());
            assertFrozen(() -> workspace.service.rename("a", "Renamed", group -> true, entity -> true));
        }
    }

    @Test
    void ignoredResourceCannotBeReportedAsConfirmedOrForcedIntoGit() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.resource.getResource(".gitignore").write("api/Orders/Third.ms\n");
            assertTrue(workspace.service.saveFile(api("c", "Third", "third", "return 3;")));
            assertThrows(GitSyncException.class, workspace.service::refresh);
            GitSyncSnapshot failed = workspace.repo.snapshot(true);
            assertEquals("LOCAL_IO_ERROR", failed.getErrorCode());
            assertEquals(0, failed.getConfirmedGeneration());
            assertNull(failed.getLastSuccessAt());
            assertEquals("return 3;", workspace.service.file("c").getScript());
            assertEquals(0, workspace.repo.pushCalls.get());
            assertFalse(workspace.repo.getCoordinator().isInconsistent());
        }
    }

    @Test
    void deletedAssumeValidResourceCannotBeConfirmedWhileRemoteStillContainsIt() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            try (Git git = Git.open(workspace.localPath.toFile())) {
                DirCache index = git.getRepository().lockDirCache();
                try {
                    index.getEntry("api/Orders/First.ms").setAssumeValid(true);
                    index.write();
                    assertTrue(index.commit());
                } finally {
                    index.unlock();
                }
            }
            assertTrue(workspace.service.delete("a"));
            assertThrows(GitSyncException.class, workspace.service::refresh);
            assertEquals("LOCAL_IO_ERROR", workspace.repo.snapshot(true).getErrorCode());
            assertEquals(0, workspace.repo.snapshot(true).getConfirmedGeneration());
            assertEquals("return 1;", workspace.remoteFile("First").getScript());
            assertFalse(Files.exists(workspace.localPath.resolve("api/Orders/First.ms")));
        }
    }

    @Test
    void directIgnoredResourceWriteCannotBeReportedAsConfirmed() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            assertTrue(workspace.resource.getResource(".gitignore").write("direct-ignored.txt\n"));
            assertTrue(workspace.resource.getResource("direct-ignored.txt").write("保留本地写入"));
            assertThrows(GitSyncException.class, workspace.service::refresh);
            assertEquals("LOCAL_IO_ERROR", workspace.repo.snapshot(true).getErrorCode());
            assertEquals(0, workspace.repo.snapshot(true).getConfirmedGeneration());
            assertEquals(0, workspace.repo.pushCalls.get());
            assertEquals("保留本地写入", new String(Files.readAllBytes(workspace.localPath.resolve("direct-ignored.txt")), StandardCharsets.UTF_8));
        }
    }

    @Test
    void failedNotificationRefreshKeepsLastCacheAndFreezesBeforeReturning() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            Files.write(workspace.localPath.resolve("api/Orders/First.ms"), "{}".getBytes(StandardCharsets.UTF_8));
            MagicNotify notification = new MagicNotify("other", "new-file", EventAction.CREATE, Constants.EVENT_TYPE_FILE);
            assertThrows(RuntimeException.class, () -> workspace.service.processNotify(notification));
            assertEquals("return 1;", workspace.service.file("a").getScript());
            assertEquals("return 2;", workspace.service.file("b").getScript());
            assertEquals("WORKSPACE_INCONSISTENT", workspace.repo.snapshot(true).getErrorCode());
            assertFrozen(() -> workspace.service.saveFile(api("c", "Third", "third", "return 3;")));
        }
    }

    @Test
    void unreadableDirectoryCannotBePublishedAsAnEmptyResourceTree() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            Path group = workspace.localPath.resolve("api/Orders");
            assumeTrue(Files.getFileStore(group).supportsFileAttributeView("posix"), "需要 POSIX 目录权限");
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(group);
            try {
                Files.setPosixFilePermissions(group, Collections.emptySet());
                assumeFalse(Files.isReadable(group), "当前用户可绕过目录权限限制");
                assertThrows(RuntimeException.class, workspace.service::refreshLocal);
                assertEquals("Orders", workspace.service.getGroup("g").getName());
                assertEquals("return 1;", workspace.service.file("a").getScript());
                assertEquals("WORKSPACE_INCONSISTENT", workspace.repo.snapshot(true).getErrorCode());
                assertEquals(0, workspace.clears.get());
            } finally {
                Files.setPosixFilePermissions(group, permissions);
            }
        }
    }

    @Test
    void failedMovePublicationRestoresLastKnownEntityBeforeFreezing() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            Group target = new Group("target", "Target");
            target.setType("api");
            target.setParentId("0");
            target.setPath("target");
            assertTrue(workspace.service.saveGroup(target));
            workspace.failPublication = EventAction.MOVE;
            assertThrows(RuntimeException.class, () -> workspace.service.move("a", "target"));
            assertEquals("g", workspace.service.file("a").getGroupId());
            assertEquals("return 1;", workspace.service.file("a").getScript());
            assertEquals("WORKSPACE_INCONSISTENT", workspace.repo.snapshot(true).getErrorCode());
            assertFrozen(() -> workspace.service.delete("b"));
        }
    }

    private void assertFailure(Future<?> future, GitSyncError expected) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(15, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof GitSyncException);
        assertEquals(expected, ((GitSyncException) failure.getCause()).getError());
    }

    private void assertFrozen(Runnable write) {
        assertEquals(1039, assertThrows(InvalidArgumentException.class, write::run).getCode());
    }
}
