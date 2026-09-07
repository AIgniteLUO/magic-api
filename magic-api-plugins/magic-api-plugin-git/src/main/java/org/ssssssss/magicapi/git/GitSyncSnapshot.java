package org.ssssssss.magicapi.git;

/** 当前服务工作区的不可变快照；generation 表示写入版本，不表示个人改动数。 */
public final class GitSyncSnapshot {
    private final boolean supported;
    private final String instanceId;
    private final long statusVersion;
    private final String taskId;
    private final String state;
    private final String phase;
    private final long localGeneration;
    private final long confirmedGeneration;
    private final Long lastAttemptAt;
    private final Long lastSuccessAt;
    private final String errorCode;
    private final String safeMessage;
    private final boolean retryable;
    private final boolean canRetry;

    GitSyncSnapshot(boolean supported, String instanceId, long statusVersion, String taskId, String state,
                    String phase, long localGeneration, long confirmedGeneration, Long lastAttemptAt,
                    Long lastSuccessAt, GitSyncError error, boolean retryable, boolean canRetry) {
        this.supported = supported;
        this.instanceId = instanceId;
        this.statusVersion = statusVersion;
        this.taskId = taskId;
        this.state = state;
        this.phase = phase;
        this.localGeneration = localGeneration;
        this.confirmedGeneration = confirmedGeneration;
        this.lastAttemptAt = lastAttemptAt;
        this.lastSuccessAt = lastSuccessAt;
        this.errorCode = error == null ? null : error.name();
        this.safeMessage = error == null ? null : error.getSafeMessage();
        this.retryable = retryable;
        this.canRetry = canRetry;
    }

    public static GitSyncSnapshot unsupported() {
        return new GitSyncSnapshot(false, null, 0, null, "unknown", null, 0, 0, null, null, null, false, false);
    }

    public boolean isSupported() { return supported; }
    public String getInstanceId() { return instanceId; }
    public long getStatusVersion() { return statusVersion; }
    public String getTaskId() { return taskId; }
    public String getState() { return state; }
    public String getPhase() { return phase; }
    public long getLocalGeneration() { return localGeneration; }
    public long getConfirmedGeneration() { return confirmedGeneration; }
    public Long getLastAttemptAt() { return lastAttemptAt; }
    public Long getLastSuccessAt() { return lastSuccessAt; }
    public String getErrorCode() { return errorCode; }
    public String getSafeMessage() { return safeMessage; }
    public boolean isRetryable() { return retryable; }
    public boolean isCanRetry() { return canRetry; }
}
