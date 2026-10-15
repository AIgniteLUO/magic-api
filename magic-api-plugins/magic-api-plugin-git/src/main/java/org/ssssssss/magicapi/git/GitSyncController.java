package org.ssssssss.magicapi.git;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.ssssssss.magicapi.core.annotation.Valid;
import org.ssssssss.magicapi.core.config.Constants;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.context.MagicUser;
import org.ssssssss.magicapi.core.exception.InvalidArgumentException;
import org.ssssssss.magicapi.core.interceptor.Authorization;
import org.ssssssss.magicapi.core.model.JsonBean;
import org.ssssssss.magicapi.core.resource.Resource;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;
import org.ssssssss.magicapi.core.web.MagicController;
import org.ssssssss.magicapi.core.web.MagicExceptionHandler;

/** Git 工作区能力由实际存储决定，安装插件本身不代表启用了 Git。 */
public class GitSyncController extends MagicController implements MagicExceptionHandler {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    public GitSyncController(MagicConfiguration configuration) {
        super(configuration);
    }

    @GetMapping("/git/sync/status")
    @Valid(authorization = Authorization.VIEW)
    @ResponseBody
    public JsonBean<GitSyncSnapshot> status(MagicHttpServletRequest request) {
        Resource workspace = configuration.getWorkspace();
        return new JsonBean<>(workspace instanceof GitResource
                ? ((GitResource) workspace).getGitRepo().snapshot(canRetry(request)) : GitSyncSnapshot.unsupported());
    }

    @PostMapping("/git/sync/retry")
    @Valid(authorization = Authorization.RELOAD, readonly = false)
    @ResponseBody
    public JsonBean<GitSyncSnapshot> retry(MagicHttpServletRequest request) {
        Resource workspace = configuration.getWorkspace();
        return new JsonBean<>(workspace instanceof GitResource
                ? ((GitResource) workspace).getGitRepo().retry(canRetry(request)) : GitSyncSnapshot.unsupported());
    }

    /** 记录列表：纯查询，不触发同步，也不改变任何同步状态。 */
    @GetMapping("/git/sync/records")
    @Valid(authorization = Authorization.VIEW)
    @ResponseBody
    public JsonBean<GitSyncRecords> records(MagicHttpServletRequest request) {
        Resource workspace = configuration.getWorkspace();
        if (!(workspace instanceof GitResource)) {
            return new JsonBean<>(GitSyncRecords.unsupported());
        }
        return new JsonBean<>(((GitResource) workspace).getGitRepo()
                .records(limit(request), parameter(request, "before")));
    }

    /** 记录详情：id 必须先通过格式校验，未命中再显式失败。 */
    @GetMapping("/git/sync/records/detail")
    @Valid(authorization = Authorization.VIEW)
    @ResponseBody
    public JsonBean<GitSyncRecordDetail> detail(MagicHttpServletRequest request) {
        String id = parameter(request, "id");
        if (!GitSyncRecord.isRecordId(id)) {
            throw new InvalidArgumentException(PARAMETER_INVALID);
        }
        Resource workspace = configuration.getWorkspace();
        if (!(workspace instanceof GitResource)) {
            // 非 Git 工作区不可能存在任何记录，显式失败而不是返回空对象
            throw new InvalidArgumentException(FILE_NOT_FOUND);
        }
        return new JsonBean<>(((GitResource) workspace).getGitRepo().detail(id));
    }

    /** 单文件差异：只有提交记录有变更集，尝试记录不参与。 */
    @GetMapping("/git/sync/records/diff")
    @Valid(authorization = Authorization.VIEW)
    @ResponseBody
    public JsonBean<GitSyncDiff> diff(MagicHttpServletRequest request) {
        String id = parameter(request, "id");
        String path = parameter(request, "path");
        if (!GitSyncRecord.isCommitId(id) || path == null || path.isEmpty()) {
            throw new InvalidArgumentException(PARAMETER_INVALID);
        }
        Resource workspace = configuration.getWorkspace();
        if (!(workspace instanceof GitResource)) {
            throw new InvalidArgumentException(FILE_NOT_FOUND);
        }
        return new JsonBean<>(((GitResource) workspace).getGitRepo().diff(id, path));
    }

    /** limit 越界钳制在 1–200，非数字按参数错误显式拒绝。 */
    private int limit(MagicHttpServletRequest request) {
        String raw = parameter(request, "limit");
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_LIMIT;
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException invalid) {
            throw new InvalidArgumentException(PARAMETER_INVALID);
        }
        return Math.max(1, Math.min(MAX_LIMIT, value));
    }

    private String parameter(MagicHttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    private boolean canRetry(MagicHttpServletRequest request) {
        MagicUser user = (MagicUser) request.getAttribute(Constants.ATTRIBUTE_MAGIC_USER);
        return !configuration.getWorkspace().readonly()
                && configuration.getAuthorizationInterceptor().allowVisit(user, request, Authorization.RELOAD);
    }
}
