package org.ssssssss.magicapi.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.ssssssss.magicapi.core.config.JsonCodeConstants;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.utils.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.ssssssss.magicapi.git.GitWorkspaceFixture.api;

/** 记录列表、详情、单文件差异与尝试流水：全部基于真实临时 bare 与两个 checkout。 */
@Timeout(60)
class GitSyncRecordsTest {
    @TempDir Path directory;

    @Test
    void recordsMergeSuccessfulCommitAndFailedAttemptInReverseOrder() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.save(workspace.service, "a", "return 11;");
            workspace.service.refresh();
            String synced = workspace.headCommit();

            workspace.repo.emptyPushResult = true;
            workspace.save(workspace.service, "b", "return 22;");
            assertThrows(GitSyncException.class, workspace.service::refresh);

            GitSyncRecords page = workspace.repo.records(50, null);
            assertTrue(page.isSupported());
            assertEquals(workspace.repo.snapshot(true).getInstanceId(), page.getInstanceId());
            assertFalse(page.isHasMore());
            assertNull(page.getNextBefore());
            // 初始提交 + 两次本地提交（第二次的推送被拒，本地提交仍然成立）；失败的推送以流水表达
            assertEquals(3, kind(page, "commit").size());
            assertEquals(1, kind(page, "attempt").size());

            GitSyncRecord commit = find(page, "commit:" + synced);
            assertNotNull(commit);
            assertEquals("confirmed", commit.getOutcome());
            assertTrue(Math.abs(commit.getAt() - System.currentTimeMillis()) < 600_000L,
                    "提交记录的 at 必须是 epoch 毫秒：" + commit.getAt());
            assertEquals(1, commit.getFileCount());
            assertNull(commit.getErrorCode());
            assertNull(commit.getRetryable());

            GitSyncRecord attempt = kind(page, "attempt").get(0);
            assertEquals("failed", attempt.getOutcome());
            assertEquals("REMOTE_REJECTED", attempt.getErrorCode());
            assertTrue(attempt.getRetryable());
            assertNull(attempt.getFileCount());
            assertTrue(attempt.getAt() >= commit.getAt(), "失败流水必须排在成功提交之后");
            assertEquals(newestFirst(page), ids(page), "列表必须按 (at, id) 倒序");
            assertEquals(new HashSet<>(ids(page)).size(), page.getRecords().size());
        }
    }

    @Test
    void failedAttemptDetailReportsPhaseClassificationAndGenerations() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.repo.emptyPushResult = true;
            workspace.save(workspace.service, "a", "return 42;");
            assertThrows(GitSyncException.class, workspace.service::refresh);

            GitSyncRecord attempt = kind(workspace.repo.records(50, null), "attempt").get(0);
            GitSyncRecordDetail detail = workspace.repo.detail(attempt.getId());
            GitSyncSnapshot snapshot = workspace.repo.snapshot(true);
            assertEquals("attempt", detail.getKind());
            assertEquals("attempt:" + detail.getSeq(), attempt.getId());
            assertEquals("failed", detail.getOutcome());
            assertEquals("push", detail.getPhase(), "失败停在推送阶段");
            assertEquals("REMOTE_REJECTED", detail.getErrorCode());
            assertTrue(detail.getRetryable());
            assertNotNull(detail.getEndedAt());
            assertTrue(detail.getEndedAt() >= detail.getStartedAt());
            assertEquals(snapshot.getLocalGeneration(), detail.getLocalGeneration().longValue());
            assertEquals(0L, detail.getConfirmedGeneration().longValue());
            assertNull(detail.getCommitId());
            assertTrue(detail.getFiles().isEmpty());
        }
    }

    @Test
    void commitDetailListsChangedFilesWithChangeTypeAndTextFlag() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.save(workspace.service, "a", "return 11;");
            assertTrue(workspace.service.saveFile(api("c", "Third", "third", "return 33;")));
            assertTrue(workspace.service.delete("b"));
            workspace.service.refresh();
            String head = workspace.headCommit();

            GitSyncRecordDetail detail = workspace.repo.detail("commit:" + head);
            assertEquals("commit", detail.getKind());
            assertEquals(head, detail.getCommitId());
            assertEquals("同步资源", detail.getMessage());
            assertEquals(Boolean.FALSE, detail.getMessageTruncated(), "短提交信息不该标记截断");
            assertNotNull(detail.getAt());
            assertNull(detail.getSeq());
            assertEquals(3, detail.getTotalFiles().intValue());
            assertEquals(Boolean.FALSE, detail.getTruncated(), "三条变更远未到上界");
            assertTrue(detail.getFiles().size() == 3, "变更文件数：" + detail.getFiles());

            Map<String, GitSyncRecordDetail.ChangedFile> files = new LinkedHashMap<>();
            detail.getFiles().forEach(file -> files.put(file.getPath(), file));
            assertEquals("MODIFY", files.get("api/Orders/First.ms").getChangeType());
            assertEquals("ADD", files.get("api/Orders/Third.ms").getChangeType());
            assertEquals("DELETE", files.get("api/Orders/Second.ms").getChangeType());
            for (GitSyncRecordDetail.ChangedFile file : detail.getFiles()) {
                assertTrue(file.isText(), file.getPath());
                assertFalse(file.getPath().startsWith("/"), file.getPath());
                assertFalse(file.getPath().contains(".."), file.getPath());
                assertFalse(file.getPath().contains(".git"), file.getPath());
            }
        }
    }

    /** 首次导入大型工作区时单次提交可能变更上千个文件：详情必须给上界并显式报告真实总数。 */
    @Test
    void commitDetailCapsTheFileListAndReportsTheRealTotal() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            Files.createDirectories(workspace.localPath.resolve("data"));
            List<String> returned = new ArrayList<>();
            for (int i = 0; i < 260; i++) {
                String path = String.format("data/f%03d.txt", i);
                assertTrue(workspace.resource.getResource(path)
                        .write(("return " + i + ";").getBytes(StandardCharsets.UTF_8)), path);
                if (i < GitSyncRecordDetail.MAX_FILES) {
                    returned.add(path);
                }
            }
            workspace.service.refresh();
            String head = "commit:" + workspace.headCommit();

            GitSyncRecordDetail detail = workspace.repo.detail(head);
            assertEquals(260, detail.getTotalFiles().intValue(), "必须给出真实文件总数");
            assertEquals(Boolean.TRUE, detail.getTruncated(), "被裁剪必须显式标记");
            assertEquals(GitSyncRecordDetail.MAX_FILES, detail.getFiles().size(), "文件清单必须给上界");
            List<String> paths = new ArrayList<>();
            detail.getFiles().forEach(file -> paths.add(file.getPath()));
            assertEquals(returned, paths, "返回按路径排序后的前 200 条");
            detail.getFiles().forEach(file -> assertTrue(file.isText(), file.getPath()));

            GitSyncDiff omitted = workspace.repo.diff(head, "data/f259.txt");
            assertTrue(omitted.isText(), "被裁掉的文件仍能按需单独取差异");
            assertTrue(omitted.getAfter().contains("return 259;"));

            String payload = JsonUtils.toJsonStringWithoutPretty(detail);
            assertTrue(payload.contains("\"totalFiles\":260"), payload);
            assertTrue(payload.contains("\"truncated\":true"), payload);
        }
    }

    /** 提交信息超过上界必须截断并显式标记：前端按 2000 字符校验，超限会让整份详情被拒绝。 */
    @Test
    void commitDetailTruncatesOversizedMessageWithAnExplicitFlag() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            String message = "外部长提交信息".repeat(500);
            Files.write(workspace.localPath.resolve("api/Orders/First.ms"),
                    "return 11;".getBytes(StandardCharsets.UTF_8));
            try (Git git = Git.open(workspace.localPath.toFile())) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage(message).call();
            }
            // 外部提交后等 GitRepo 内缓存的 ref 快照刷新
            Thread.sleep(200);

            GitSyncRecordDetail detail = workspace.repo.detail("commit:" + workspace.headCommit());
            assertEquals(GitSyncRecordDetail.MAX_MESSAGE_CHARS, detail.getMessage().length(), "提交信息必须给上界");
            assertEquals(Boolean.TRUE, detail.getMessageTruncated());
            assertEquals(message.substring(0, GitSyncRecordDetail.MAX_MESSAGE_CHARS), detail.getMessage());
            assertTrue(JsonUtils.toJsonStringWithoutPretty(detail).contains("\"messageTruncated\":true"),
                    "序列化结果必须带上截断标记");
        }
    }

    @Test
    void singleFileDiffReturnsBothSidesForModifyAddAndDelete() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            Path first = workspace.localPath.resolve("api/Orders/First.ms");
            String before = read(first);
            workspace.save(workspace.service, "a", "return 11;");
            assertTrue(workspace.service.saveFile(api("c", "Third", "third", "return 33;")));
            assertTrue(workspace.service.delete("b"));
            workspace.service.refresh();
            String head = "commit:" + workspace.headCommit();

            GitSyncDiff modified = workspace.repo.diff(head, "api/Orders/First.ms");
            assertEquals("api/Orders/First.ms", modified.getPath());
            assertTrue(modified.isText());
            assertFalse(modified.isTruncated());
            assertEquals(before, modified.getBefore());
            assertEquals(read(first), modified.getAfter());
            assertNotEquals(modified.getBefore(), modified.getAfter());

            GitSyncDiff added = workspace.repo.diff(head, "api/Orders/Third.ms");
            assertTrue(added.isText());
            assertEquals("", added.getBefore());
            assertTrue(added.getAfter().contains("return 33;"));

            GitSyncDiff deleted = workspace.repo.diff(head, "api/Orders/Second.ms");
            assertTrue(deleted.isText());
            assertEquals("", deleted.getAfter());
            assertTrue(deleted.getBefore().contains("return 2;"));
        }
    }

    @Test
    void binaryAndOversizedFilesAreReportedInsteadOfBeingTruncatedSilently() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            assertTrue(workspace.resource.getResource("bin.dat").write(new byte[] {1, 0, 2, 3, 4}));
            byte[] large = new byte[GitTextContent.MAX_BYTES + 1024];
            Arrays.fill(large, (byte) 'a');
            assertTrue(workspace.resource.getResource("large.txt").write(large));
            workspace.service.refresh();
            String head = "commit:" + workspace.headCommit();

            GitSyncDiff binary = workspace.repo.diff(head, "bin.dat");
            assertFalse(binary.isText());
            assertNull(binary.getBefore(), "二进制不返回内容");
            assertNull(binary.getAfter(), "二进制不返回内容");
            assertFalse(binary.isTruncated());

            GitSyncDiff oversized = workspace.repo.diff(head, "large.txt");
            assertTrue(oversized.isText());
            assertTrue(oversized.isTruncated());
            assertEquals(GitTextContent.MAX_BYTES, oversized.getAfter().length(), "超限时给出上限内的前缀");
            assertEquals("", oversized.getBefore());

            GitSyncRecordDetail detail = workspace.repo.detail(head);
            Map<String, Boolean> text = new LinkedHashMap<>();
            detail.getFiles().forEach(file -> text.put(file.getPath(), file.isText()));
            assertEquals(Boolean.FALSE, text.get("bin.dat"));
            assertEquals(Boolean.TRUE, text.get("large.txt"));
        }
    }

    @Test
    void illegalUnknownAndOutOfChangeSetParametersAreRejectedExplicitly() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.save(workspace.service, "a", "return 11;");
            workspace.service.refresh();
            String head = "commit:" + workspace.headCommit();

            for (String path : Arrays.asList("../secret.txt", "api/Orders/../../etc/passwd", "/etc/passwd",
                    ".git/config", "api/./Orders/First.ms", "api//Orders/First.ms", ".git/../api/Orders/First.ms",
                    "api/Orders/\\..\\secret")) {
                assertEquals(JsonCodeConstants.PARAMETER_INVALID.getCode(), codeOf(() -> workspace.repo.diff(head, path)), path);
            }
            assertEquals(JsonCodeConstants.PARAMETER_INVALID.getCode(),
                    codeOf(() -> workspace.repo.diff("attempt:1", "api/Orders/First.ms")), "差异只对提交记录有意义");
            assertEquals(JsonCodeConstants.PARAMETER_INVALID.getCode(),
                    codeOf(() -> workspace.repo.records(50, "not-a-cursor")));

            assertEquals(JsonCodeConstants.FILE_NOT_FOUND.getCode(),
                    codeOf(() -> workspace.repo.diff(head, "api/Orders/Second.ms")), "不在本次变更集内");
            assertEquals(JsonCodeConstants.FILE_NOT_FOUND.getCode(),
                    codeOf(() -> workspace.repo.detail("commit:" + repeat("f", 40))));
            assertEquals(JsonCodeConstants.FILE_NOT_FOUND.getCode(), codeOf(() -> workspace.repo.detail(zeros())));
            assertEquals(JsonCodeConstants.FILE_NOT_FOUND.getCode(), codeOf(() -> workspace.repo.detail("attempt:987654")));
            assertEquals(JsonCodeConstants.FILE_NOT_FOUND.getCode(),
                    codeOf(() -> workspace.repo.diff("commit:" + repeat("f", 40), "api/Orders/First.ms")));
            assertEquals(JsonCodeConstants.PARAMETER_INVALID.getCode(),
                    codeOf(() -> workspace.repo.detail("commit:not-a-sha")));
        }
    }

    @Test
    void cursorKeepsSameMillisecondRecordsWithoutLossOrDuplication() throws Exception {
        long at = 1727000000000L;
        GitSyncAttemptLog log = new GitSyncAttemptLog();
        long running = log.begin(at, 1, 0);
        long failed = log.begin(at, 1, 0);
        log.fail(failed, at + 20, "push", GitSyncError.REMOTE_REJECTED, 1, 0);
        assertEquals("running", log.find(running).getOutcome());
        assertEquals("failed", log.find(failed).getOutcome());

        List<GitSyncRecord> attempts = new ArrayList<>();
        attempts.add(GitSyncRecord.attempt(log.find(failed)));
        attempts.add(GitSyncRecord.attempt(log.find(running)));
        List<GitSyncRecord> commits = Collections.singletonList(GitSyncRecord.commit(ObjectId.zeroId(), at, 3));

        List<String> expected = Arrays.asList("commit:" + ObjectId.zeroId().name(), "attempt:2", "attempt:1");
        List<String> collected = new ArrayList<>();
        String before = null;
        for (int page = 0; page < 5; page++) {
            GitSyncRecords records = GitSyncRecords.page("instance", commits, attempts, 1, GitSyncCursor.parse(before));
            assertTrue(records.isSupported());
            collected.addAll(ids(records));
            if (!records.isHasMore()) {
                assertNull(records.getNextBefore(), "末页不再给出游标");
                break;
            }
            assertNotNull(records.getNextBefore());
            before = records.getNextBefore();
        }
        assertEquals(expected, collected, "同毫秒并列必须不丢不重");
        assertEquals(new HashSet<>(expected).size(), collected.size());

        // 提交侧为本页做 diff 的裁剪必须与分页同向：同一毫秒内也不能把游标之前的记录算进来
        GitSyncCursor cursor = GitSyncCursor.parse(GitSyncCursor.of(commits.get(0)).encode());
        assertTrue(GitSyncRecords.select(commits, cursor, 5).isEmpty());
        assertEquals(Arrays.asList("attempt:2", "attempt:1"), ids(GitSyncRecords.select(attempts, cursor, 5)));
        assertEquals(Arrays.asList("attempt:2"), ids(GitSyncRecords.select(attempts, cursor, 1)));

        assertEquals(JsonCodeConstants.PARAMETER_INVALID.getCode(), codeOf(() -> GitSyncCursor.parse("###")));
        assertEquals(JsonCodeConstants.PARAMETER_INVALID.getCode(),
                codeOf(() -> GitSyncCursor.parse(GitSyncCursor.START.encode())));
    }

    @Test
    void realHistoryPagesWithoutLossOrDuplication() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            for (int i = 1; i <= 3; i++) {
                workspace.save(workspace.service, "a", "return " + (10 + i) + ";");
                workspace.service.refresh();
            }
            workspace.repo.emptyPushResult = true;
            assertThrows(GitSyncException.class, workspace.service::refresh);

            List<String> expected = ids(workspace.repo.records(100, null));
            assertEquals(5, expected.size(), "初始提交 + 3 次同步提交 + 1 条失败流水：" + expected);

            List<String> collected = new ArrayList<>();
            String before = null;
            boolean completed = false;
            for (int page = 0; page < expected.size() + 1; page++) {
                GitSyncRecords records = workspace.repo.records(2, before);
                collected.addAll(ids(records));
                if (!records.isHasMore()) {
                    completed = true;
                    break;
                }
                before = records.getNextBefore();
            }
            assertTrue(completed, "分页没有在预期页数内结束：" + collected);
            assertEquals(expected, collected);
            assertEquals(new HashSet<>(expected).size(), collected.size());
        }
    }

    @Test
    void restartKeepsCommitsAndDropsAttemptLog() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.save(workspace.service, "a", "return 21;");
            workspace.service.refresh();
            workspace.repo.emptyPushResult = true;
            assertThrows(GitSyncException.class, workspace.service::refresh);

            GitSyncRecords live = workspace.repo.records(50, null);
            assertFalse(kind(live, "attempt").isEmpty(), "失败尝试必须留痕");
            List<String> commits = ids(kind(live, "commit"));
            assertFalse(commits.isEmpty());
            String instance = live.getInstanceId();

            workspace.repo.close();
            try (GitRepo restarted = new GitRepo(workspace.localPath.toString(), workspace.properties)) {
                restarted.setupRepo();
                GitSyncRecords after = restarted.records(50, null);
                assertTrue(after.isSupported());
                assertNotEquals(instance, after.getInstanceId());
                assertTrue(kind(after, "attempt").isEmpty(), "尝试流水不落盘，重启即空");
                assertEquals(commits, ids(kind(after, "commit")), "提交历史是权威证据，重启仍在");
            }
        }
    }

    /** 风险 2 实测：对当前页每条提交计算变更文件数的耗时是否可接受。 */
    @Test
    void recordsPageComputesFileCountWithinBudget() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            for (int i = 1; i <= 50; i++) {
                workspace.save(workspace.service, "a", "return " + (100 + i) + ";");
                workspace.save(workspace.service, "b", "return " + (200 + i) + ";");
                workspace.service.refresh();
            }
            workspace.repo.records(50, null);
            long best = Long.MAX_VALUE;
            for (int round = 0; round < 3; round++) {
                long start = System.nanoTime();
                GitSyncRecords page = workspace.repo.records(50, null);
                long elapsed = System.nanoTime() - start;
                best = Math.min(best, elapsed);
                assertEquals(50, page.getRecords().size());
                assertEquals(2, page.getRecords().get(0).getFileCount(), "最新一次同步提交改动 2 个文件");
            }
            System.out.println("风险2实测：50 条提交首页（含逐条首父 diff 计算 fileCount）最快 "
                    + best / 1000 + " 微秒，平均单条 " + best / 50 / 1000 + " 微秒");
            assertTrue(best < 2_000_000_000L, "首页 50 条提交的文件数计算耗时超预算：" + best / 1_000_000 + " ms");
        }
    }

    /** 记录读取与同步共用单 worker：等待上限内的查询必须排队，且等待期间不持状态锁与资源锁。 */
    @Test
    void recordQueriesWaitForTheSingleWorkerWithoutHoldingLocks() throws Exception {
        try (GitWorkspaceFixture workspace = new GitWorkspaceFixture(directory)) {
            workspace.save(workspace.service, "a", "return 11;");
            GitWorkspaceFixture.Gate fetch = workspace.repo.blockFetch();
            Future<?> reload = workspace.reloadAsync();
            fetch.awaitEntered();

            AtomicBoolean returned = new AtomicBoolean();
            Future<GitSyncRecords> records = workspace.clients.submit(() -> {
                GitSyncRecords page = workspace.repo.records(5, null);
                returned.set(true);
                return page;
            });
            Thread.sleep(500);
            assertFalse(returned.get(), "同步尚未结束时记录查询不能返回：读路径必须排在单 worker 之后");
            // 等待期间状态读取与资源读取都不得被查询阻塞
            assertNotNull(workspace.repo.snapshot(true).getInstanceId(), "等待期间状态锁被占用");
            Future<?> file = workspace.clients.submit(() -> workspace.service.file("a"));
            assertNotNull(file.get(5, TimeUnit.SECONDS), "等待期间资源读锁被占用");

            fetch.release();
            reload.get(30, TimeUnit.SECONDS);
            GitSyncRecords page = records.get(30, TimeUnit.SECONDS);
            assertTrue(page.isSupported());
            assertEquals(2, kind(page, "commit").size(), "同步结束后查询必须能看到本次同步提交");
        }
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static List<GitSyncRecord> kind(GitSyncRecords page, String kind) {
        List<GitSyncRecord> records = new ArrayList<>();
        for (GitSyncRecord record : page.getRecords()) {
            if (kind.equals(record.getKind())) {
                records.add(record);
            }
        }
        return records;
    }

    private static GitSyncRecord find(GitSyncRecords page, String id) {
        for (GitSyncRecord record : page.getRecords()) {
            if (id.equals(record.getId())) {
                return record;
            }
        }
        return null;
    }

    private static List<String> ids(GitSyncRecords page) {
        return ids(page.getRecords());
    }

    private static List<String> ids(List<GitSyncRecord> records) {
        List<String> ids = new ArrayList<>();
        for (GitSyncRecord record : records) {
            ids.add(record.getId());
        }
        return ids;
    }

    private static List<String> newestFirst(GitSyncRecords page) {
        List<GitSyncRecord> sorted = new ArrayList<>(page.getRecords());
        sorted.sort(GitSyncRecord.NEWEST_FIRST);
        return ids(sorted);
    }

    private static int codeOf(Executable action) {
        return assertThrows(InvalidArgumentException.class, action).getCode();
    }

    private static String zeros() {
        return "commit:" + repeat("0", 40);
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
