package org.ssssssss.magicapi.git;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ssssssss.magicapi.core.config.JsonCodeConstants;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.service.WorkspaceResourceCoordinator;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * 单 worker 拥有全部 JGit 操作。状态锁不做 I/O；fetch/push 不持资源锁。
 * 先提交最新本地内容，再进行内存合并，明确收到目标 ref 确认后才推进同步版本。
 */
public class GitRepo implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(GitRepo.class);
    private static final long DEBOUNCE_MILLIS = 500;
    private static final int NETWORK_TIMEOUT_SECONDS = 30;
    private static final int RELOAD_TIMEOUT_SECONDS = 60;
    /** 记录查询排在同步任务之后，等待上限覆盖一次完整的 fetch + push 超时。 */
    private static final int QUERY_TIMEOUT_SECONDS = 60;
    private static final String REMOTE = "origin";
    private static final String RECOVERY_FILE = "magic-api-workspace-inconsistent";
    private static final String COMMIT_MESSAGE = "同步资源";

    private final File root;
    private final MagicGitProperties properties;
    private final WorkspaceResourceCoordinator coordinator = new WorkspaceResourceCoordinator();
    private final Object stateLock = new Object();
    private final String instanceId = UUID.randomUUID().toString();
    private final ScheduledExecutorService executor;
    private Git git;
    private String targetRef;
    private Path recoveryFile;
    private long checkpointGeneration;
    // 由资源锁保护。缓存移除的旧路径及未进入缓存的直接写入也必须核验。
    private final Set<Path> changedPaths = new HashSet<>();

    // 以下字段仅在 stateLock 内读写。任务完成回调在锁外运行。
    private long statusVersion;
    private long localGeneration;
    private long confirmedGeneration;
    private String state = "unknown";
    private String phase;
    private String taskId;
    private Long lastAttemptAt;
    private Long lastSuccessAt;
    private GitSyncError error;
    private ScheduledFuture<?> scheduled;
    private CompletableFuture<GitSyncSnapshot> task;
    private boolean running;
    private boolean closed;
    // 尝试流水是进程内证据，不落盘、不改变状态版本，只受 stateLock 保护。
    private final GitSyncAttemptLog attempts = new GitSyncAttemptLog();
    private long runningAttempt = -1;

    public GitRepo(String rootPath, MagicGitProperties properties) {
        this.root = new File(rootPath);
        this.properties = properties;
        this.executor = Executors.newSingleThreadScheduledExecutor(action -> {
            Thread thread = new Thread(action, "magic-api-git-sync");
            thread.setDaemon(true);
            return thread;
        });
        coordinator.onInconsistent(this::workspaceFailed);
    }

    public WorkspaceResourceCoordinator getCoordinator() {
        return coordinator;
    }

    public MagicGitProperties getProperties() {
        return properties;
    }

    /** 初始化也在 worker 中执行，已有仓库只核验本地信息，不执行 pull。 */
    public void setupRepo() throws IOException, GitAPIException {
        coordinator.assertCanWait();
        try {
            executor.submit(() -> {
                try {
                    openRepository();
                    inspectStartup();
                } catch (IOException | GitAPIException failure) {
                    throw new GitSyncException(GitSyncError.classify(failure));
                }
            }).get(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GitSyncException(GitSyncError.UNKNOWN_ERROR);
        } catch (ExecutionException failure) {
            throw new GitSyncException(GitSyncError.classify(failure.getCause()));
        } catch (TimeoutException timeout) {
            throw new GitSyncException(GitSyncError.NETWORK_ERROR);
        }
    }

    private void openRepository() throws IOException, GitAPIException {
        if (new File(root, Constants.DOT_GIT).exists()) {
            git = Git.open(root);
        } else {
            Files.createDirectories(root.toPath());
            String[] entries = root.list();
            if (entries == null || entries.length > 0) {
                throw new GitSyncException(GitSyncError.LOCAL_IO_ERROR);
            }
            CloneCommand clone = Git.cloneRepository().setURI(properties.getUrl()).setDirectory(root);
            if (StringUtils.isNotBlank(properties.getBranch())) {
                clone.setBranch(properties.getBranch());
            }
            configureTransport(clone);
            git = clone.call();
        }
        recoveryFile = git.getRepository().getDirectory().toPath().resolve(RECOVERY_FILE);
        String branch = StringUtils.defaultIfBlank(properties.getBranch(), git.getRepository().getFullBranch());
        targetRef = branch != null && branch.startsWith(Constants.R_HEADS) ? branch : Constants.R_HEADS + branch;
        if (!Repository.isValidRefName(targetRef)) {
            throw new GitSyncException(GitSyncError.LOCAL_IO_ERROR);
        }
    }

    private void inspectStartup() throws IOException, GitAPIException {
        if (Files.exists(recoveryFile)) {
            coordinator.write(() -> {
                coordinator.markInconsistent();
                return null;
            });
            return;
        }
        try {
            checkRepositoryState();
        } catch (GitSyncException failure) {
            synchronized (stateLock) {
                error = failure.getError();
                changeState("failed", "checkpoint");
            }
            return;
        }
        Repository repository = git.getRepository();
        ObjectId head = repository.resolve(Constants.HEAD);
        ObjectId tracking = repository.resolve(trackingRef());
        boolean dirty = !git.status().call().isClean();
        boolean unconfirmed = head != null && (tracking == null || !isAncestor(head, tracking));
        synchronized (stateLock) {
            if (dirty || unconfirmed) {
                localGeneration = 1;
                changeState("pending", null);
            } else {
                // 即使本地 tracking ref 与 HEAD 相同，也不能据此承诺远端已确认。
                changeState("unknown", null);
            }
        }
    }

    /** 只记录已落盘写入；调用方须处于资源协调边界内。 */
    public boolean update(boolean ignored) {
        synchronized (stateLock) {
            if (closed) {
                throw new GitSyncException(GitSyncError.LOCAL_IO_ERROR);
            }
            localGeneration++;
            statusVersion++;
            if (!"failed".equals(state)) {
                if (!running) {
                    changeState("pending", null);
                    schedule(DEBOUNCE_MILLIS, true);
                }
            }
            return true;
        }
    }

    void recordMutation(Path previous, Path current) {
        coordinator.recordMutation();
        changedPaths.add(previous.toAbsolutePath().normalize());
        changedPaths.add(current.toAbsolutePath().normalize());
        update(false);
    }

    /** 查询完全无副作用。 */
    public GitSyncSnapshot snapshot(boolean authorizedToRetry) {
        synchronized (stateLock) {
            return snapshotLocked(authorizedToRetry);
        }
    }

    public GitSyncSnapshot retry(boolean authorizedToRetry) {
        synchronized (stateLock) {
            if (authorizedToRetry && !closed && (error == null || error.isRetryable())) {
                schedule(0, false);
            }
            return snapshotLocked(authorizedToRetry);
        }
    }

    /**
     * 记录查询是纯查询：不触发同步、不改变状态版本、不调度任务、不取资源锁。
     * 尝试流水先于提交历史取样，否则同步进行中永远看不到 running 记录；
     * 提交历史只在单 worker 执行 JGit，等待期间不持状态锁。
     */
    public GitSyncRecords records(int limit, String before) {
        GitSyncCursor cursor = GitSyncCursor.parse(before);
        String instance;
        List<GitSyncRecord> attemptRecords = new ArrayList<>();
        synchronized (stateLock) {
            if (closed) {
                return GitSyncRecords.unsupported();
            }
            instance = instanceId;
            for (GitSyncAttemptLog.Attempt attempt : attempts.snapshot()) {
                attemptRecords.add(GitSyncRecord.attempt(attempt));
            }
        }
        return GitSyncRecords.page(instance, query(() -> commitRecords(cursor, limit + 1)), attemptRecords, limit, cursor);
    }

    /** 尝试记录只读内存流水，提交记录只在当前分支历史内可读。 */
    public GitSyncRecordDetail detail(String id) {
        if (!GitSyncRecord.isRecordId(id)) {
            throw new InvalidArgumentException(JsonCodeConstants.PARAMETER_INVALID);
        }
        if (id.startsWith(GitSyncRecord.ATTEMPT_PREFIX)) {
            long sequence = GitSyncRecord.attemptSequence(id);
            synchronized (stateLock) {
                GitSyncAttemptLog.Attempt attempt = attempts.find(sequence);
                if (attempt == null) {
                    throw new InvalidArgumentException(JsonCodeConstants.FILE_NOT_FOUND);
                }
                return GitSyncRecordDetail.attempt(attempt);
            }
        }
        return query(() -> commitDetail(GitSyncRecord.commitId(id)));
    }

    /** 差异只对提交记录有意义；路径先在进入 worker 之前完成规范化校验。 */
    public GitSyncDiff diff(String id, String path) {
        if (!GitSyncRecord.isCommitId(id)) {
            throw new InvalidArgumentException(JsonCodeConstants.PARAMETER_INVALID);
        }
        String commitId = GitSyncRecord.commitId(id);
        String normalized = GitTextContent.normalizePath(path);
        return query(() -> commitDiff(commitId, normalized));
    }

    /**
     * 提交历史按 (at, id) 倒序取页；位置判定与列表分页共用 GitSyncRecords.select，
     * 不依赖 JGit 的遍历顺序。文件数只对本次返回的提交计算，不为整段历史做差异。
     */
    private List<GitSyncRecord> commitRecords(GitSyncCursor cursor, int max) throws IOException {
        Repository repository = repository();
        List<GitSyncRecord> candidates = new ArrayList<>();
        Map<String, ObjectId> commitsById = new LinkedHashMap<>();
        ObjectId head = repository.resolve(Constants.HEAD);
        if (head != null) {
            try (RevWalk walk = new RevWalk(repository)) {
                walk.setRetainBody(false);
                walk.markStart(walk.parseCommit(head));
                for (RevCommit commit : walk) {
                    // copy()：不把 walk 内部的 RevObject 带出遍历
                    GitSyncRecord record = GitSyncRecord.commit(commit.copy(), commit.getCommitTime() * 1000L, null);
                    candidates.add(record);
                    commitsById.put(record.getId(), commit.copy());
                }
            }
        }
        List<GitSyncRecord> records = new ArrayList<>();
        try (ObjectReader reader = repository.newObjectReader();
             RevWalk walk = new RevWalk(repository);
             DiffFormatter formatter = newDiffFormatter(repository)) {
            for (GitSyncRecord candidate : GitSyncRecords.select(candidates, cursor, max)) {
                records.add(candidate.withFileCount(
                        diffEntries(walk, reader, formatter, commitsById.get(candidate.getId())).size()));
            }
        }
        return records;
    }

    private GitSyncRecordDetail commitDetail(String commitId) throws IOException {
        Repository repository = repository();
        try (ObjectReader reader = repository.newObjectReader();
             RevWalk walk = new RevWalk(repository);
             DiffFormatter formatter = newDiffFormatter(repository)) {
            RevCommit commit = reachableCommit(walk, repository, commitId);
            if (commit == null) {
                throw new InvalidArgumentException(JsonCodeConstants.FILE_NOT_FOUND);
            }
            List<DiffEntry> entries = new ArrayList<>(diffEntries(walk, reader, formatter, commit.getId()));
            entries.sort(Comparator.comparing(GitRepo::entryPath));
            // 首次导入大型工作区可能变更上千个文件：先按路径排序取上界，
            // text 只对实际返回的文件读取（每条约 2 次对象读取），总数与截断标记显式返回。
            List<GitSyncRecordDetail.ChangedFile> files = new ArrayList<>(
                    Math.min(entries.size(), GitSyncRecordDetail.MAX_FILES));
            for (int i = 0; i < entries.size() && i < GitSyncRecordDetail.MAX_FILES; i++) {
                DiffEntry entry = entries.get(i);
                files.add(new GitSyncRecordDetail.ChangedFile(entryPath(entry), entry.getChangeType().name(),
                        GitTextContent.read(reader, blobId(entry.getOldId())).isText()
                                && GitTextContent.read(reader, blobId(entry.getNewId())).isText()));
            }
            return GitSyncRecordDetail.commit(commit.getId().name(), commit.getCommitTime() * 1000L,
                    commit.getFullMessage(), files, entries.size());
        }
    }

    /** 路径必须落在该提交的变更集内，规范化之后仍相等才允许读取内容。 */
    private GitSyncDiff commitDiff(String commitId, String path) throws IOException {
        Repository repository = repository();
        try (ObjectReader reader = repository.newObjectReader();
             RevWalk walk = new RevWalk(repository);
             DiffFormatter formatter = newDiffFormatter(repository)) {
            RevCommit commit = reachableCommit(walk, repository, commitId);
            if (commit == null) {
                throw new InvalidArgumentException(JsonCodeConstants.FILE_NOT_FOUND);
            }
            for (DiffEntry entry : diffEntries(walk, reader, formatter, commit.getId())) {
                if (!path.equals(entryPath(entry))) {
                    continue;
                }
                GitTextContent.Side before = GitTextContent.read(reader, blobId(entry.getOldId()));
                GitTextContent.Side after = GitTextContent.read(reader, blobId(entry.getNewId()));
                boolean text = before.isText() && after.isText();
                return new GitSyncDiff(path, text, text ? before.getValue() : null, text ? after.getValue() : null,
                        before.isTruncated() || after.isTruncated());
            }
            throw new InvalidArgumentException(JsonCodeConstants.FILE_NOT_FOUND);
        }
    }

    /** 只接受当前分支可达的提交：悬空对象或历史之外的提交不能通过记录接口读取。 */
    private RevCommit reachableCommit(RevWalk walk, Repository repository, String commitId) throws IOException {
        ObjectId target = repository.resolve(commitId);
        ObjectId head = repository.resolve(Constants.HEAD);
        if (target == null || head == null) {
            return null;
        }
        walk.markStart(walk.parseCommit(head));
        for (RevCommit commit : walk) {
            if (commit.getId().equals(target)) {
                return commit;
            }
        }
        return null;
    }

    /** 与首父提交比较；关闭重命名检测，改名呈现为 ADD + DELETE，避免额外成本与歧义。 */
    private List<DiffEntry> diffEntries(RevWalk walk, ObjectReader reader, DiffFormatter formatter, ObjectId commitId)
            throws IOException {
        RevCommit commit = walk.parseCommit(commitId);
        // 父提交必须显式 parse，否则它的 tree 字段尚未解析
        RevCommit parent = commit.getParentCount() == 0 ? null : walk.parseCommit(commit.getParent(0));
        return formatter.scan(parent == null ? new EmptyTreeIterator() : new CanonicalTreeParser(null, reader, parent.getTree()),
                new CanonicalTreeParser(null, reader, commit.getTree()));
    }

    private DiffFormatter newDiffFormatter(Repository repository) {
        DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE);
        formatter.setRepository(repository);
        formatter.setDetectRenames(false);
        return formatter;
    }

    /** 删除文件的 newPath 是 /dev/null，路径取真实存在的一侧。 */
    private static String entryPath(DiffEntry entry) {
        return entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath();
    }

    private static ObjectId blobId(AbbreviatedObjectId abbreviated) {
        return abbreviated == null || !abbreviated.isComplete() ? null : abbreviated.toObjectId();
    }

    /** 读操作同样只在单 worker 执行 JGit；等待期间不持状态锁，也不持资源锁。 */
    private <T> T query(LocalAction<T> action) {
        Future<T> future;
        synchronized (stateLock) {
            if (closed) {
                throw new GitSyncException(GitSyncError.LOCAL_IO_ERROR);
            }
            future = executor.submit(() -> action.run());
        }
        try {
            return future.get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GitSyncException(GitSyncError.UNKNOWN_ERROR);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof InvalidArgumentException) {
                // 参数非法与记录不存在由调用方显式判定，不能重分类成同步失败
                throw (InvalidArgumentException) cause;
            }
            // 只记录异常类型，不记录可能含凭证、绝对路径和换行的异常原文
            logger.warn("Git 记录查询异常类型：{}", cause == null ? "null" : cause.getClass().getName());
            throw new GitSyncException(GitSyncError.classify(cause));
        } catch (TimeoutException timeout) {
            future.cancel(false);
            throw new GitSyncException(GitSyncError.UNKNOWN_ERROR);
        }
    }

    private Repository repository() throws IOException {
        if (git == null) {
            // 仓库尚未打开：显性失败，不把空历史当成"没有记录"
            throw new GitSyncException(GitSyncError.LOCAL_IO_ERROR);
        }
        return git.getRepository();
    }

    /** reload 复用当前任务，等待时不能持有资源读锁或写锁。 */
    public void pullLatest() {
        coordinator.assertCanWait();
        CompletableFuture<GitSyncSnapshot> waiting;
        synchronized (stateLock) {
            if (closed || coordinator.isInconsistent()) {
                throw new GitSyncException(coordinator.isInconsistent()
                        ? GitSyncError.WORKSPACE_INCONSISTENT : GitSyncError.LOCAL_IO_ERROR);
            }
            // 人工处理普通冲突或本地 I/O 后，显式 reload 可重新核验；一致性冻结只可专门恢复。
            waiting = schedule(0, false);
        }
        try {
            GitSyncSnapshot result = waiting.get(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if ("failed".equals(result.getState())) {
                throw new GitSyncException(GitSyncError.valueOf(result.getErrorCode()));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GitSyncException(GitSyncError.UNKNOWN_ERROR);
        } catch (ExecutionException failure) {
            throw new GitSyncException(GitSyncError.classify(failure.getCause()));
        } catch (TimeoutException timeout) {
            // 等待超时不取消后台任务，也不把结果未知伪装成失败的远端确认。
            throw new GitSyncException(GitSyncError.NETWORK_ERROR);
        }
    }

    private CompletableFuture<GitSyncSnapshot> schedule(long delay, boolean debounce) {
        if (task == null) {
            task = new CompletableFuture<>();
            taskId = UUID.randomUUID().toString();
            statusVersion++;
        }
        if (!running && (scheduled == null || debounce || delay == 0)) {
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            CompletableFuture<GitSyncSnapshot> scheduledTask = task;
            scheduled = executor.schedule(() -> synchronize(scheduledTask), delay, TimeUnit.MILLISECONDS);
            if (!"failed".equals(state)) {
                changeState("pending", null);
            }
        }
        return task;
    }

    /**
     * 供维护入口显式调用：管理员先修复工作树和索引，再核验并重建缓存。
     * 不执行 reset/checkout，不接受客户端传入的提交，不由 retry 或定时器调用。
     */
    public void verifyWorkspaceRecovery() {
        coordinator.assertCanWait();
        Future<?> recovery;
        synchronized (stateLock) {
            if (closed || task != null || running) {
                throw new GitSyncException(GitSyncError.WORKSPACE_INCONSISTENT);
            }
            recovery = executor.submit(() -> coordinator.recover(() -> {
                try {
                    checkRepositoryState();
                    if (!git.status().call().isClean()) {
                        throw new GitSyncException(GitSyncError.WORKSPACE_INCONSISTENT);
                    }
                    verifyTrackedResources();
                } catch (IOException | GitAPIException failure) {
                    throw new GitSyncException(GitSyncError.WORKSPACE_INCONSISTENT);
                }
            }, () -> {
                try {
                    verifyTrackedResources();
                    Files.deleteIfExists(recoveryFile);
                    changedPaths.clear();
                } catch (IOException failure) {
                    throw new GitSyncException(GitSyncError.WORKSPACE_INCONSISTENT);
                }
                synchronized (stateLock) {
                    error = null;
                    localGeneration++;
                    confirmedGeneration = 0;
                    lastSuccessAt = null;
                    changeState("pending", null);
                }
            }));
        }
        try {
            recovery.get(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GitSyncException(GitSyncError.WORKSPACE_INCONSISTENT);
        } catch (ExecutionException | TimeoutException failure) {
            throw new GitSyncException(GitSyncError.WORKSPACE_INCONSISTENT);
        }
    }

    private void synchronize(CompletableFuture<GitSyncSnapshot> currentTask) {
        synchronized (stateLock) {
            if (closed || currentTask != task) {
                return;
            }
            scheduled = null;
            running = true;
            lastAttemptAt = System.currentTimeMillis();
            // 尝试流水只记分类与阶段；成功与否仍以提交历史为准。
            runningAttempt = attempts.begin(lastAttemptAt, localGeneration, confirmedGeneration);
            changeState("syncing", "checkpoint");
        }
        logger.info("Git 同步状态：syncing，阶段：checkpoint");
        GitSyncError failureCode = null;
        PushTarget pushed = null;
        try {
            local(() -> { checkpoint(); return null; });
            setPhase("fetch");
            FetchResult fetched = fetch();
            Ref fetchedRef = fetched.getAdvertisedRef(targetRef);
            ObjectId remote = fetchedRef == null ? null : fetchedRef.getObjectId();
            setPhase("integrate");
            pushed = local(() -> {
                checkpoint();
                integrate(remote);
                verifyTrackedResources();
                ObjectId commit = git.getRepository().resolve(Constants.HEAD);
                if (commit == null) {
                    throw new GitSyncException(GitSyncError.LOCAL_IO_ERROR);
                }
                synchronized (stateLock) {
                    return new PushTarget(commit, localGeneration);
                }
            });
            setPhase("push");
            confirmPush(push(pushed.commit), pushed.commit);
        } catch (Exception failure) {
            failureCode = coordinator.isInconsistent() ? GitSyncError.WORKSPACE_INCONSISTENT : GitSyncError.classify(failure);
            // 异常类型可定位故障层，但不记录可能含凭证、绝对路径和换行的异常原文。
            logger.warn("Git 同步异常类型：{}", failure.getClass().getSimpleName());
        }
        GitSyncSnapshot result;
        synchronized (stateLock) {
            running = false;
            task = null;
            if (coordinator.isInconsistent()) {
                failureCode = GitSyncError.WORKSPACE_INCONSISTENT;
            }
            String finalPhase = phase;
            if (failureCode != null) {
                error = failureCode;
                changeState("failed", phase);
            } else {
                confirmedGeneration = Math.max(confirmedGeneration, pushed.generation);
                lastSuccessAt = System.currentTimeMillis();
                error = null;
                changeState(localGeneration > confirmedGeneration ? "pending" : "idle", null);
            }
            if (failureCode == null) {
                // 已确认的同步由提交历史表达，流水只留无法确认的尝试
                attempts.discard(runningAttempt);
            } else {
                attempts.fail(runningAttempt, System.currentTimeMillis(), finalPhase, failureCode,
                        localGeneration, confirmedGeneration);
            }
            runningAttempt = -1;
            result = snapshotLocked(true);
            if (!closed && failureCode == null && localGeneration > confirmedGeneration) {
                schedule(DEBOUNCE_MILLIS, false);
            }
        }
        logger.info("Git 同步状态：{}，阶段：{}，错误分类：{}", result.getState(), result.getPhase(), result.getErrorCode());
        currentTask.complete(result);
    }

    private void checkpoint() throws IOException, GitAPIException {
        checkRepositoryState();
        Status status = git.status().call();
        if (!status.isClean()) {
            synchronized (stateLock) {
                // 同时纳入进程外写入，但不把多个物理写入误称为资源数。
                if (localGeneration == checkpointGeneration) {
                    localGeneration++;
                    statusVersion++;
                }
            }
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
            verifyTrackedResources();
            if (git.status().call().hasUncommittedChanges()) {
                git.commit().setMessage(COMMIT_MESSAGE).call();
            }
        }
        verifyTrackedResources();
        changedPaths.clear();
        synchronized (stateLock) {
            checkpointGeneration = localGeneration;
        }
    }

    private void verifyTrackedResources() throws IOException {
        DirCache index = git.getRepository().readDirCache();
        for (int i = 0; i < index.getEntryCount(); i++) {
            DirCacheEntry entry = index.getEntry(i);
            // 已删除资源不再出现在缓存中；索引跳过标记仍可能让删除被判作 clean。
            if (entry.isAssumeValid() || entry.isSkipWorkTree()) {
                throw new GitSyncException(GitSyncError.LOCAL_IO_ERROR);
            }
        }
        Set<String> paths = new HashSet<>(coordinator.getResourcePaths());
        Path workspace = root.toPath().toAbsolutePath().normalize();
        for (Path changed : changedPaths) {
            if (!Files.exists(changed)) {
                continue;
            }
            Files.walkFileTree(changed, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    return directory.equals(workspace.resolve(Constants.DOT_GIT))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (!file.equals(workspace.resolve(Constants.DOT_GIT))) {
                        paths.add(workspace.relativize(file).toString().replace(File.separatorChar, '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        for (String path : paths) {
            if (Files.isRegularFile(root.toPath().resolve(path))) {
                DirCacheEntry entry = index.getEntry(path);
                // 不能把被 ignore 或索引跳过的资源写入计作远端已确认，更不能强行纳入忽略文件。
                if (entry == null) {
                    throw new GitSyncException(GitSyncError.LOCAL_IO_ERROR);
                }
            }
        }
    }

    private void checkRepositoryState() throws IOException, GitAPIException {
        Repository repository = git.getRepository();
        if (!Objects.equals(repository.getFullBranch(), targetRef)) {
            throw new GitSyncException(GitSyncError.LOCAL_IO_ERROR);
        }
        if (repository.getRepositoryState() != RepositoryState.SAFE || !git.status().call().getConflicting().isEmpty()) {
            throw new GitSyncException(GitSyncError.MERGE_CONFLICT);
        }
    }

    /** 仅网络/refs 操作，不能访问资源协调锁。测试在此边界阻塞真实传输。 */
    protected FetchResult fetch() throws GitAPIException {
        FetchCommand command = git.fetch().setRemote(REMOTE)
                .setRefSpecs(new RefSpec("+refs/heads/*:refs/remotes/" + REMOTE + "/*"));
        configureTransport(command);
        return command.call();
    }

    protected Iterable<PushResult> push(ObjectId commit) throws GitAPIException {
        PushCommand command = git.push().setRemote(REMOTE)
                .setRefSpecs(new RefSpec(commit.name() + ":" + targetRef));
        configureTransport(command);
        return command.call();
    }

    private void confirmPush(Iterable<PushResult> results, ObjectId commit) {
        boolean confirmed = false;
        if (results != null) {
            for (PushResult result : results) {
                boolean destinationConfirmed = false;
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    if (targetRef.equals(update.getRemoteName())) {
                        RemoteRefUpdate.Status status = update.getStatus();
                        if ((status != RemoteRefUpdate.Status.OK && status != RemoteRefUpdate.Status.UP_TO_DATE)
                                || !commit.equals(update.getNewObjectId())) {
                            throw new GitSyncException(GitSyncError.REMOTE_REJECTED);
                        }
                        destinationConfirmed = true;
                    }
                }
                if (!destinationConfirmed) {
                    throw new GitSyncException(GitSyncError.REMOTE_REJECTED);
                }
                confirmed = true;
            }
        }
        if (!confirmed) {
            throw new GitSyncException(GitSyncError.REMOTE_REJECTED);
        }
    }

    private void integrate(ObjectId remote) throws IOException {
        Repository repository = git.getRepository();
        ObjectId head = repository.resolve(Constants.HEAD);
        ObjectId target = head;
        if (remote != null && !remote.equals(head) && (head == null || !isAncestor(remote, head))) {
            target = head == null || isAncestor(head, remote) ? remote : mergeInMemory(head, remote);
        }
        if (target != null && !target.equals(head)) {
            applyCommit(head, target);
        } else {
            // 即使树没有变化，显式 reload 仍重新核验缓存，且不会递归触发网络。
            coordinator.refreshLocal();
        }
    }

    private boolean isAncestor(ObjectId ancestor, ObjectId descendant) throws IOException {
        try (RevWalk walk = new RevWalk(git.getRepository())) {
            return walk.isMergedInto(walk.parseCommit(ancestor), walk.parseCommit(descendant));
        }
    }

    private ObjectId mergeInMemory(ObjectId head, ObjectId remote) throws IOException {
        Repository repository = git.getRepository();
        ResolveMerger merger = (ResolveMerger) MergeStrategy.RECURSIVE.newMerger(repository, true);
        if (!merger.merge(head, remote)) {
            throw new GitSyncException(GitSyncError.MERGE_CONFLICT);
        }
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            CommitBuilder merged = new CommitBuilder();
            merged.setTreeId(merger.getResultTreeId());
            merged.setParentIds(head, remote);
            PersonIdent identity = new PersonIdent(repository);
            merged.setAuthor(identity);
            merged.setCommitter(identity);
            merged.setMessage("整合远端资源");
            ObjectId commit = inserter.insert(merged);
            inserter.flush();
            return commit;
        }
    }

    private void applyCommit(ObjectId head, ObjectId target) throws IOException {
        Repository repository = git.getRepository();
        try (RevWalk walk = new RevWalk(repository)) {
            ObjectId oldTree = head == null ? null : walk.parseCommit(head).getTree();
            ObjectId newTree = walk.parseCommit(target).getTree();
            DirCache index = repository.lockDirCache();
            try {
                DirCacheCheckout checkout = new DirCacheCheckout(repository, oldTree, index, newTree);
                checkout.setFailOnConflict(true);
                if (oldTree == null) {
                    checkout.prescanOneTree();
                } else {
                    checkout.preScanTwoTrees();
                }
                if (!checkout.getConflicts().isEmpty()) {
                    throw new GitSyncException(GitSyncError.MERGE_CONFLICT);
                }
                // 先持久化恢复点，再改任何工作文件；重启时必须保留冻结，不能自动提交半个树。
                Files.write(recoveryFile, ((head == null ? ObjectId.zeroId().name() : head.name()) + "\n"
                        + target.name() + "\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    applyTree(checkout);
                    RefUpdate update = repository.updateRef(Constants.HEAD);
                    update.setExpectedOldObjectId(head == null ? ObjectId.zeroId() : head);
                    update.setNewObjectId(target);
                    update.setRefLogMessage("magic-api integrate", false);
                    RefUpdate.Result result = update.update();
                    if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD
                            && result != RefUpdate.Result.NO_CHANGE) {
                        throw new IOException("本地分支更新未确认");
                    }
                    coordinator.refreshLocal();
                    Files.delete(recoveryFile);
                } catch (IOException | RuntimeException failure) {
                    coordinator.markInconsistent();
                    throw new GitSyncException(GitSyncError.WORKSPACE_INCONSISTENT);
                }
            } finally {
                index.unlock();
            }
        }
    }

    /** checkout 返回 false 也已可能改动工作区，必须冻结，不能按成功继续。 */
    protected void applyTree(DirCacheCheckout checkout) throws IOException {
        if (!checkout.checkout()) {
            throw new IOException("本地文件未全部应用");
        }
    }

    private <T> T local(LocalAction<T> action) {
        return coordinator.write(() -> {
            try {
                return action.run();
            } catch (IOException | GitAPIException failure) {
                throw new GitSyncException(GitSyncError.classify(failure));
            }
        });
    }

    private void configureTransport(TransportCommand<?, ?> command) {
        command.setTimeout(NETWORK_TIMEOUT_SECONDS);
        if (StringUtils.isNotBlank(properties.getPrivateKey())) {
            SshSessionFactory factory = new JschConfigSessionFactory() {
                @Override
                protected void configure(OpenSshConfig.Host host, Session session) { }

                @Override
                protected JSch createDefaultJSch(FS fs) throws JSchException {
                    JSch jsch = super.createDefaultJSch(fs);
                    jsch.addIdentity(properties.getPrivateKey());
                    return jsch;
                }
            };
            command.setTransportConfigCallback(transport -> {
                if (transport instanceof SshTransport) {
                    ((SshTransport) transport).setSshSessionFactory(factory);
                }
            });
        } else if (StringUtils.isNotBlank(properties.getUsername()) && properties.getPassword() != null) {
            command.setCredentialsProvider(new UsernamePasswordCredentialsProvider(properties.getUsername(), properties.getPassword()));
        }
        // URI 中的 oauth2/userinfo 由 JGit 统一解析，不重复截取或记录敏感 URL。
    }

    private String trackingRef() {
        return Constants.R_REMOTES + REMOTE + "/" + targetRef.substring(Constants.R_HEADS.length());
    }

    private GitSyncSnapshot snapshotLocked(boolean authorized) {
        boolean retryable = !closed && !coordinator.isInconsistent() && (error == null || error.isRetryable());
        return new GitSyncSnapshot(true, instanceId, statusVersion, taskId, state, phase, localGeneration,
                confirmedGeneration, lastAttemptAt, lastSuccessAt, error, retryable, authorized && retryable && !running);
    }

    private void setPhase(String nextPhase) {
        synchronized (stateLock) {
            if (coordinator.isInconsistent()) {
                throw new GitSyncException(GitSyncError.WORKSPACE_INCONSISTENT);
            }
            attempts.phase(runningAttempt, nextPhase);
            changeState("syncing", nextPhase);
        }
        logger.info("Git 同步状态：syncing，阶段：{}", nextPhase);
    }

    private void changeState(String nextState, String nextPhase) {
        state = nextState;
        phase = nextPhase;
        statusVersion++;
    }

    private void workspaceFailed() {
        if (recoveryFile != null && !Files.exists(recoveryFile)) {
            try {
                Files.write(recoveryFile, "需要人工核验资源工作区\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            } catch (IOException failure) {
                logger.error("无法持久化工作区冻结标记；当前进程已禁止继续写入");
            }
        }
        synchronized (stateLock) {
            error = GitSyncError.WORKSPACE_INCONSISTENT;
            changeState("failed", phase);
        }
    }

    @Override
    public void close() {
        CompletableFuture<GitSyncSnapshot> pending;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            pending = task;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
        executor.shutdownNow();
        if (pending != null) {
            pending.completeExceptionally(new GitSyncException(GitSyncError.LOCAL_IO_ERROR));
        }
        try {
            if (executor.awaitTermination(5, TimeUnit.SECONDS) && git != null) {
                git.close();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private interface LocalAction<T> {
        T run() throws IOException, GitAPIException;
    }

    private static final class PushTarget {
        final ObjectId commit;
        final long generation;

        PushTarget(ObjectId commit, long generation) {
            this.commit = commit;
            this.generation = generation;
        }
    }
}
