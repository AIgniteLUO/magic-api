package org.ssssssss.magicapi.git;

import java.util.Collections;
import java.util.List;

/**
 * 单条记录的详情：提交给文件清单，尝试给结果、错误分类、阶段与代次。
 * 不返回作者、远端 URL、绝对目录、凭证或异常原文。
 * 上界一律显式表达：文件清单最多 {@link #MAX_FILES} 条（同时给真实总数 totalFiles 与 truncated），
 * 提交信息最多 {@link #MAX_MESSAGE_CHARS} 个字符（同时给 messageTruncated），不静默截断。
 */
public final class GitSyncRecordDetail {
    /** 首次导入大型工作区时单次提交可能变更上千个文件，读路径必须给出上界。 */
    static final int MAX_FILES = 200;
    /** 前端按 2000 字符校验提交信息，超出必须截断并标记，否则整份详情会被拒绝。 */
    static final int MAX_MESSAGE_CHARS = 2000;

    private final String kind;
    private final String commitId;
    private final Long at;
    private final String message;
    private final Boolean messageTruncated;
    private final List<ChangedFile> files;
    private final Long totalFiles;
    private final Boolean truncated;
    private final Long seq;
    private final Long startedAt;
    private final Long endedAt;
    private final String outcome;
    private final String phase;
    private final String errorCode;
    private final Boolean retryable;
    private final Long localGeneration;
    private final Long confirmedGeneration;

    private GitSyncRecordDetail(String kind, String commitId, Long at, String message, Boolean messageTruncated,
                                List<ChangedFile> files, Long totalFiles, Boolean truncated,
                                Long seq, Long startedAt, Long endedAt, String outcome, String phase,
                                String errorCode, Boolean retryable, Long localGeneration, Long confirmedGeneration) {
        this.kind = kind;
        this.commitId = commitId;
        this.at = at;
        this.message = message;
        this.messageTruncated = messageTruncated;
        this.files = files;
        this.totalFiles = totalFiles;
        this.truncated = truncated;
        this.seq = seq;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.outcome = outcome;
        this.phase = phase;
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.localGeneration = localGeneration;
        this.confirmedGeneration = confirmedGeneration;
    }

    /** 文件清单由调用方按上界裁剪，总数与截断标记在这里统一推导，两处不会不一致。 */
    static GitSyncRecordDetail commit(String commitId, long at, String message, List<ChangedFile> files, int totalFiles) {
        return new GitSyncRecordDetail(GitSyncRecord.COMMIT_KIND, commitId, at, truncate(message),
                message != null && message.length() > MAX_MESSAGE_CHARS, files, (long) totalFiles,
                totalFiles > files.size(), null, null, null, "confirmed", null, null, null, null, null);
    }

    static GitSyncRecordDetail attempt(GitSyncAttemptLog.Attempt attempt) {
        return new GitSyncRecordDetail(GitSyncRecord.ATTEMPT_KIND, null, null, null, null,
                Collections.emptyList(), null, null,
                attempt.getSeq(), attempt.getStartedAt(), attempt.getEndedAt(), attempt.getOutcome(), attempt.getPhase(),
                attempt.getErrorCode(), attempt.getRetryable(), attempt.getLocalGeneration(), attempt.getConfirmedGeneration());
    }

    /** 按 UTF-16 字符截断（与前端 length 口径一致），不把代理对切成半个字符。 */
    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_MESSAGE_CHARS) {
            return message;
        }
        int end = Character.isHighSurrogate(message.charAt(MAX_MESSAGE_CHARS - 1)) ? MAX_MESSAGE_CHARS - 1 : MAX_MESSAGE_CHARS;
        return message.substring(0, end);
    }

    public String getKind() { return kind; }
    public String getCommitId() { return commitId; }
    public Long getAt() { return at; }
    public String getMessage() { return message; }
    public Boolean getMessageTruncated() { return messageTruncated; }
    public List<ChangedFile> getFiles() { return files; }
    public Long getTotalFiles() { return totalFiles; }
    public Boolean getTruncated() { return truncated; }
    public Long getSeq() { return seq; }
    public Long getStartedAt() { return startedAt; }
    public Long getEndedAt() { return endedAt; }
    public String getOutcome() { return outcome; }
    public String getPhase() { return phase; }
    public String getErrorCode() { return errorCode; }
    public Boolean getRetryable() { return retryable; }
    public Long getLocalGeneration() { return localGeneration; }
    public Long getConfirmedGeneration() { return confirmedGeneration; }

    /** 该提交变更集里的一条文件：工作区相对路径、变更类型、是否可安全展示为文本。 */
    public static final class ChangedFile {
        private final String path;
        private final String changeType;
        private final boolean text;

        ChangedFile(String path, String changeType, boolean text) {
            this.path = path;
            this.changeType = changeType;
            this.text = text;
        }

        public String getPath() { return path; }
        public String getChangeType() { return changeType; }
        public boolean isText() { return text; }
    }
}
