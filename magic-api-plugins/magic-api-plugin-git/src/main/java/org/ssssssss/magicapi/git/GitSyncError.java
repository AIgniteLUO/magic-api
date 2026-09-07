package org.ssssssss.magicapi.git;

import org.eclipse.jgit.api.errors.TransportException;

import java.io.IOException;
import java.util.Locale;

/** 对外仅发布固定分类，绝不透传传输层的 URL、凭证、目录或异常原文。 */
public enum GitSyncError {
    AUTHENTICATION_FAILED("Git 认证失败，请联系管理员检查凭证", true),
    NETWORK_ERROR("暂时无法连接 Git 远端，请稍后重试", true),
    REMOTE_REJECTED("Git 远端拒绝推送，请检查分支权限或远端变更", true),
    MERGE_CONFLICT("本地与远端存在冲突，请管理员手动处理后重新加载", false),
    LOCAL_IO_ERROR("Git 本地操作失败，请管理员检查工作区后重新加载", false),
    WORKSPACE_INCONSISTENT("资源工作区一致性异常，已暂停写入，请管理员核验恢复", false),
    UNKNOWN_ERROR("Git 同步失败，请联系管理员检查服务日志", true);

    private final String safeMessage;
    private final boolean retryable;

    GitSyncError(String safeMessage, boolean retryable) {
        this.safeMessage = safeMessage;
        this.retryable = retryable;
    }

    public String getSafeMessage() {
        return safeMessage;
    }

    public boolean isRetryable() {
        return retryable;
    }

    static GitSyncError classify(Throwable failure) {
        boolean localIo = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof GitSyncException) {
                return ((GitSyncException) cause).getError();
            }
            if (cause instanceof TransportException || cause instanceof org.eclipse.jgit.errors.TransportException) {
                String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
                if (message.contains("auth") || message.contains("not authorized") || message.contains("permission denied")
                        || message.contains("401") || message.contains("403") || message.contains("credentials")) {
                    return AUTHENTICATION_FAILED;
                }
                return NETWORK_ERROR;
            }
            localIo |= cause instanceof IOException;
        }
        return localIo ? LOCAL_IO_ERROR : UNKNOWN_ERROR;
    }
}
