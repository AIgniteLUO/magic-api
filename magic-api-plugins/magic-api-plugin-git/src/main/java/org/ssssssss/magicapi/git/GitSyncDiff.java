package org.ssssssss.magicapi.git;

/** 提交内单个文件的文本前后对照；超限只给前缀并显式标记，二进制不返回内容。 */
public final class GitSyncDiff {
    private final String path;
    private final boolean text;
    private final String before;
    private final String after;
    private final boolean truncated;

    GitSyncDiff(String path, boolean text, String before, String after, boolean truncated) {
        this.path = path;
        this.text = text;
        this.before = before;
        this.after = after;
        this.truncated = truncated;
    }

    public String getPath() { return path; }
    public boolean isText() { return text; }
    public String getBefore() { return before; }
    public String getAfter() { return after; }
    public boolean isTruncated() { return truncated; }
}
