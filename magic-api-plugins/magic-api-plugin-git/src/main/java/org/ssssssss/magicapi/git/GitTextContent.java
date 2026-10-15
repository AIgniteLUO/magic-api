package org.ssssssss.magicapi.git;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.ssssssss.magicapi.core.config.JsonCodeConstants;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 单文件内容的安全读取：单侧上限、二进制识别、工作区相对路径校验。
 * 只读内容本身，不返回绝对目录、作者或异常原文。
 */
final class GitTextContent {
    /** 单侧上限：超限只给前缀并显式标记 truncated，不静默截断。 */
    static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_PATH_LENGTH = 4096;

    private GitTextContent() {
    }

    /**
     * 规范化并校验工作区相对路径：拒绝绝对路径、反斜杠、空段、`.`、`..` 与任意层级的 `.git`。
     * 规范化之后还必须落在该提交的变更集内，两道校验缺一不可。
     */
    static String normalizePath(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_PATH_LENGTH
                || raw.charAt(0) == '/' || raw.indexOf('\\') >= 0 || raw.indexOf('\0') >= 0) {
            throw invalidPath();
        }
        for (String segment : raw.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) || ".git".equalsIgnoreCase(segment)) {
                throw invalidPath();
            }
        }
        return raw;
    }

    /** 读取一侧内容；null 或零 id 表示该侧不存在（新增文件的 before、删除文件的 after）。 */
    static Side read(ObjectReader reader, ObjectId blobId) throws IOException {
        if (blobId == null || ObjectId.zeroId().equals(blobId)) {
            return Side.EMPTY;
        }
        ObjectLoader loader = reader.open(blobId);
        long size = loader.getSize();
        boolean truncated = size > MAX_BYTES;
        byte[] bytes = prefix(loader, (int) Math.min(size, MAX_BYTES));
        if (containsNul(bytes)) {
            return new Side(false, null, truncated);
        }
        String value = decodeUtf8(bytes, !truncated);
        return value == null ? new Side(false, null, truncated) : new Side(true, value, truncated);
    }

    private static byte[] prefix(ObjectLoader loader, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        try (InputStream stream = loader.openStream()) {
            while (offset < length) {
                int read = stream.read(bytes, offset, length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        }
        return offset == length ? bytes : Arrays.copyOf(bytes, offset);
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    /** 严格 UTF-8：解码失败即不是可安全展示的文本；被截断时末尾不完整字符不算失败。 */
    private static String decodeUtf8(byte[] bytes, boolean complete) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer decoded = CharBuffer.allocate(bytes.length + 1);
        CoderResult result = decoder.decode(ByteBuffer.wrap(bytes), decoded, complete);
        if (result.isError()) {
            return null;
        }
        decoded.flip();
        return decoded.toString();
    }

    private static InvalidArgumentException invalidPath() {
        return new InvalidArgumentException(JsonCodeConstants.PARAMETER_INVALID);
    }

    /** 一侧内容的可展示形态。 */
    static final class Side {
        static final Side EMPTY = new Side(true, "", false);

        private final boolean text;
        private final String value;
        private final boolean truncated;

        private Side(boolean text, String value, boolean truncated) {
            this.text = text;
            this.value = value;
            this.truncated = truncated;
        }

        boolean isText() { return text; }
        String getValue() { return value; }
        boolean isTruncated() { return truncated; }
    }
}
