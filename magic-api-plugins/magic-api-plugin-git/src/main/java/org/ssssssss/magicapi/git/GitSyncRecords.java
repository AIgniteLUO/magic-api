package org.ssssssss.magicapi.git;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 记录列表：提交历史与进程内尝试流水按 (at, id) 倒序合并后的一页。
 * 名单只含白名单字段；nextBefore 是不透明游标，客户端原样回传。
 */
public final class GitSyncRecords {
    private final boolean supported;
    private final String instanceId;
    private final List<GitSyncRecord> records;
    private final boolean hasMore;
    private final String nextBefore;

    private GitSyncRecords(boolean supported, String instanceId, List<GitSyncRecord> records, boolean hasMore, String nextBefore) {
        this.supported = supported;
        this.instanceId = instanceId;
        this.records = records;
        this.hasMore = hasMore;
        this.nextBefore = nextBefore;
    }

    static GitSyncRecords unsupported() {
        return new GitSyncRecords(false, null, Collections.emptyList(), false, null);
    }

    /**
     * 选出严格排在游标之后、按 (at, id) 倒序的前 max 条。
     * 列表分页与"提交侧只为本页做 diff"必须用同一处判定，否则游标会误判导致丢记录或重复。
     */
    static List<GitSyncRecord> select(List<GitSyncRecord> records, GitSyncCursor cursor, int max) {
        List<GitSyncRecord> selected = new ArrayList<>(records.size());
        for (GitSyncRecord record : records) {
            if (cursor.accepts(record.getAt(), record.getId())) {
                selected.add(record);
            }
        }
        selected.sort(GitSyncRecord.NEWEST_FIRST);
        return selected.size() > max ? new ArrayList<>(selected.subList(0, max)) : selected;
    }

    /**
     * 合并两路记录并取游标之后的一页。
     * commits 由调用方按 diff 成本预先裁剪到 limit + 1 条（游标之后），attempts 是全量流水，
     * 因此 hasMore 是精确值：多于 limit 条就说明还有更早的记录。
     */
    static GitSyncRecords page(String instanceId, List<GitSyncRecord> commits, List<GitSyncRecord> attempts,
                               int limit, GitSyncCursor cursor) {
        List<GitSyncRecord> merged = new ArrayList<>(commits);
        merged.addAll(attempts);
        List<GitSyncRecord> selected = select(merged, cursor, limit + 1);
        boolean hasMore = selected.size() > limit;
        List<GitSyncRecord> page = hasMore ? new ArrayList<>(selected.subList(0, limit)) : selected;
        String nextBefore = hasMore && !page.isEmpty()
                ? GitSyncCursor.of(page.get(page.size() - 1)).encode() : null;
        return new GitSyncRecords(true, instanceId, page, hasMore, nextBefore);
    }

    public boolean isSupported() { return supported; }
    public String getInstanceId() { return instanceId; }
    public List<GitSyncRecord> getRecords() { return records; }
    public boolean isHasMore() { return hasMore; }
    public String getNextBefore() { return nextBefore; }
}
