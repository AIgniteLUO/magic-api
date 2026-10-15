package org.ssssssss.magicapi.git;

import org.ssssssss.magicapi.core.config.JsonCodeConstants;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 记录列表的不透明游标，位置是 (at, id) 倒序中的一条记录。
 * 同一毫秒可能并列多条记录，必须靠 id 决定顺序，否则翻页会丢记录或重复。
 * 游标只承载位置，不承载任何工作区内容，客户端原样回传。
 */
final class GitSyncCursor {
    /** 记录 id 不可能含空格（提交是 16 进制、流水是数字），分隔符因此无歧义。 */
    private static final String SEPARATOR = " ";
    /** 无游标：排在所有真实记录之前（真实记录的时间远小于 Long.MAX_VALUE）。 */
    static final GitSyncCursor START = new GitSyncCursor(Long.MAX_VALUE, "");

    private final long at;
    private final String id;

    private GitSyncCursor(long at, String id) {
        this.at = at;
        this.id = id;
    }

    static GitSyncCursor of(GitSyncRecord record) {
        return new GitSyncCursor(record.getAt(), record.getId());
    }

    /** 非法游标显式拒绝，不静默当成首页，否则客户端会看到重复记录。 */
    static GitSyncCursor parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return START;
        }
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor();
        }
        int split = decoded.indexOf(SEPARATOR);
        if (split <= 0 || !GitSyncRecord.isRecordId(decoded.substring(split + SEPARATOR.length()))) {
            throw invalidCursor();
        }
        try {
            return new GitSyncCursor(Long.parseLong(decoded.substring(0, split)),
                    decoded.substring(split + SEPARATOR.length()));
        } catch (NumberFormatException invalid) {
            throw invalidCursor();
        }
    }

    /** 是否严格排在游标之后（更旧）；提交与流水、分页与裁剪共用这一处判定。 */
    boolean accepts(long at, String id) {
        if (at != this.at) {
            return at < this.at;
        }
        return id.compareTo(this.id) < 0;
    }

    /** (at, id) 倒序比较，是全列表唯一的排序判据；必须与 accepts 同向。 */
    static int compareDesc(long leftAt, String leftId, long rightAt, String rightId) {
        if (leftAt != rightAt) {
            return leftAt > rightAt ? -1 : 1;
        }
        return rightId.compareTo(leftId);
    }

    String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((at + SEPARATOR + id).getBytes(StandardCharsets.UTF_8));
    }

    private static InvalidArgumentException invalidCursor() {
        return new InvalidArgumentException(JsonCodeConstants.PARAMETER_INVALID);
    }
}
