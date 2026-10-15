package org.ssssssss.magicapi.git;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 本进程观察到的同步尝试流水：有界、不落盘，只保存固定分类。
 * 只保留失败尝试与进行中的尝试：成功的权威记录是提交历史，再留一条流水只会与提交记录重复。
 * 语义边界：这里只表达"本进程观察到且未确认的尝试"，重启即空。
 * 由 GitRepo 的状态锁保护，自身不加锁、不做 I/O；绝不保存异常原文、远端 URL 或路径。
 */
final class GitSyncAttemptLog {
    static final int DEFAULT_CAPACITY = 50;

    private final int capacity;
    private final ArrayDeque<Attempt> attempts = new ArrayDeque<>();
    private long sequence;

    GitSyncAttemptLog() {
        this(DEFAULT_CAPACITY);
    }

    /** 容量用于约束内存，不改变"只保留最近若干次未确认尝试"的语义。 */
    GitSyncAttemptLog(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** 建一条 running 记录并返回其序号，超容量的最旧记录被丢弃。 */
    long begin(long startedAt, long localGeneration, long confirmedGeneration) {
        long seq = ++sequence;
        attempts.addLast(new Attempt(seq, startedAt, localGeneration, confirmedGeneration, "running", "checkpoint", null));
        while (attempts.size() > capacity) {
            attempts.removeFirst();
        }
        return seq;
    }

    void phase(long seq, String phase) {
        Attempt current = find(seq);
        if (current != null) {
            replace(seq, current.atPhase(phase));
        }
    }

    /** 结束处落定结果、错误分类、结束时间与代次。 */
    void fail(long seq, long endedAt, String phase, GitSyncError error, long localGeneration, long confirmedGeneration) {
        Attempt current = find(seq);
        if (current != null) {
            replace(seq, current.failed(endedAt, phase, error, localGeneration, confirmedGeneration));
        }
    }

    /** 同步已确认时不留流水：该次同步由提交历史表达。 */
    void discard(long seq) {
        replace(seq, null);
    }

    Attempt find(long seq) {
        for (Attempt attempt : attempts) {
            if (attempt.getSeq() == seq) {
                return attempt;
            }
        }
        return null;
    }

    /** 返回不可变副本，允许在状态锁外读取。 */
    List<Attempt> snapshot() {
        return new ArrayList<>(attempts);
    }

    private void replace(long seq, Attempt updated) {
        attempts.removeIf(attempt -> attempt.getSeq() == seq);
        if (updated != null) {
            attempts.addLast(updated);
        }
    }

    /** 单次尝试的不可变视图：只含固定分类与时间、阶段、代次。 */
    static final class Attempt {
        private final long seq;
        private final long startedAt;
        private final Long endedAt;
        private final long localGeneration;
        private final long confirmedGeneration;
        private final String outcome;
        private final String phase;
        private final GitSyncError error;

        private Attempt(long seq, long startedAt, long localGeneration, long confirmedGeneration,
                        String outcome, String phase, GitSyncError error) {
            this(seq, startedAt, null, localGeneration, confirmedGeneration, outcome, phase, error);
        }

        private Attempt(long seq, long startedAt, Long endedAt, long localGeneration, long confirmedGeneration,
                        String outcome, String phase, GitSyncError error) {
            this.seq = seq;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.localGeneration = localGeneration;
            this.confirmedGeneration = confirmedGeneration;
            this.outcome = outcome;
            this.phase = phase;
            this.error = error;
        }

        Attempt atPhase(String nextPhase) {
            return new Attempt(seq, startedAt, endedAt, localGeneration, confirmedGeneration, outcome, nextPhase, error);
        }

        Attempt failed(long endedAt, String phase, GitSyncError error, long localGeneration, long confirmedGeneration) {
            return new Attempt(seq, startedAt, endedAt, localGeneration, confirmedGeneration, "failed", phase, error);
        }

        long getSeq() { return seq; }
        long getStartedAt() { return startedAt; }
        Long getEndedAt() { return endedAt; }
        long getLocalGeneration() { return localGeneration; }
        long getConfirmedGeneration() { return confirmedGeneration; }
        String getOutcome() { return outcome; }
        String getPhase() { return phase; }
        String getErrorCode() { return error == null ? null : error.name(); }
        Boolean getRetryable() { return error == null ? null : error.isRetryable(); }
    }
}
