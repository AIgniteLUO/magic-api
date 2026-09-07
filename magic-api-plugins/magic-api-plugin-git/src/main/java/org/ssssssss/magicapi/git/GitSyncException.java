package org.ssssssss.magicapi.git;

import org.ssssssss.magicapi.core.exception.MagicAPIException;

/** HTTP 和任务等待者只得到固定安全文本。 */
final class GitSyncException extends MagicAPIException {
    private final GitSyncError error;

    GitSyncException(GitSyncError error) {
        super(error.getSafeMessage());
        this.error = error;
    }

    GitSyncError getError() {
        return error;
    }
}
