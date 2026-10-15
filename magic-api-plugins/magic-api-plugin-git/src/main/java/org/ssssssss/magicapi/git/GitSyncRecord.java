package org.ssssssss.magicapi.git;

import org.eclipse.jgit.lib.ObjectId;
import org.ssssssss.magicapi.core.config.JsonCodeConstants;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;

import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * 记录列表项：成功记录来自提交历史，进行中/失败记录来自进程内尝试流水。
 * 只发布白名单字段，不含作者、远端 URL、绝对目录、凭证或异常原文。
 */
public final class GitSyncRecord {
    static final String COMMIT_KIND = "commit";
    static final String ATTEMPT_KIND = "attempt";
    static final String COMMIT_PREFIX = COMMIT_KIND + ":";
    static final String ATTEMPT_PREFIX = ATTEMPT_KIND + ":";
    private static final Pattern COMMIT_ID = Pattern.compile("^commit:[0-9a-f]{40}$");
    private static final Pattern RECORD_ID = Pattern.compile("^(commit:[0-9a-f]{40}|attempt:\\d{1,19})$");

    /** 记录按 (at, id) 倒序输出；同毫秒并列由 id 定序，游标才能不丢不重。 */
    static final Comparator<GitSyncRecord> NEWEST_FIRST = (left, right) ->
            GitSyncCursor.compareDesc(left.at, left.id, right.at, right.id);

    private final String id;
    private final String kind;
    private final long at;
    private final String outcome;
    private final Integer fileCount;
    private final String errorCode;
    private final Boolean retryable;

    private GitSyncRecord(String id, String kind, long at, String outcome, Integer fileCount,
                          String errorCode, Boolean retryable) {
        this.id = id;
        this.kind = kind;
        this.at = at;
        this.outcome = outcome;
        this.fileCount = fileCount;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /** 提交记录；文件数只在选出当前页之后计算，未计算时传 null。 */
    static GitSyncRecord commit(ObjectId commitId, long at, Integer fileCount) {
        return new GitSyncRecord(COMMIT_PREFIX + commitId.name(), COMMIT_KIND, at, "confirmed", fileCount, null, null);
    }

    static GitSyncRecord attempt(GitSyncAttemptLog.Attempt attempt) {
        return new GitSyncRecord(ATTEMPT_PREFIX + attempt.getSeq(), ATTEMPT_KIND, attempt.getStartedAt(),
                attempt.getOutcome(), null, attempt.getErrorCode(), attempt.getRetryable());
    }

    GitSyncRecord withFileCount(int count) {
        return new GitSyncRecord(id, kind, at, outcome, count, errorCode, retryable);
    }

    static boolean isCommitId(String id) {
        return id != null && COMMIT_ID.matcher(id).matches();
    }

    static boolean isRecordId(String id) {
        return id != null && RECORD_ID.matcher(id).matches();
    }

    static String commitId(String id) {
        return id.substring(COMMIT_PREFIX.length());
    }

    /** 序号超出 long 或非数字一律按参数错误显式拒绝，不抛出 500。 */
    static long attemptSequence(String id) {
        try {
            return Long.parseLong(id.substring(ATTEMPT_PREFIX.length()));
        } catch (NumberFormatException | IndexOutOfBoundsException invalid) {
            throw new InvalidArgumentException(JsonCodeConstants.PARAMETER_INVALID);
        }
    }

    public String getId() { return id; }
    public String getKind() { return kind; }
    public long getAt() { return at; }
    public String getOutcome() { return outcome; }
    public Integer getFileCount() { return fileCount; }
    public String getErrorCode() { return errorCode; }
    public Boolean getRetryable() { return retryable; }
}
