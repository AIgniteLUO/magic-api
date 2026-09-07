package org.ssssssss.magicapi.git;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.ssssssss.magicapi.core.annotation.Valid;
import org.ssssssss.magicapi.core.config.Constants;
import org.ssssssss.magicapi.core.config.MagicConfiguration;
import org.ssssssss.magicapi.core.context.MagicUser;
import org.ssssssss.magicapi.core.interceptor.Authorization;
import org.ssssssss.magicapi.core.model.JsonBean;
import org.ssssssss.magicapi.core.resource.Resource;
import org.ssssssss.magicapi.core.servlet.MagicHttpServletRequest;
import org.ssssssss.magicapi.core.web.MagicController;
import org.ssssssss.magicapi.core.web.MagicExceptionHandler;

/** Git 工作区能力由实际存储决定，安装插件本身不代表启用了 Git。 */
public class GitSyncController extends MagicController implements MagicExceptionHandler {
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

    private boolean canRetry(MagicHttpServletRequest request) {
        MagicUser user = (MagicUser) request.getAttribute(Constants.ATTRIBUTE_MAGIC_USER);
        return !configuration.getWorkspace().readonly()
                && configuration.getAuthorizationInterceptor().allowVisit(user, request, Authorization.RELOAD);
    }
}
