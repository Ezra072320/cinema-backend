package edu.czu.cinema.ai.service;

import edu.czu.cinema.common.api.ResultCode;
import edu.czu.cinema.common.exception.BusinessException;
import org.springframework.ai.chat.model.ToolContext;

/** 身份只由服务端注入，不能成为模型可填写的工具参数。支持 Reactor 切线程。 */
public record AiIdentity(Long userId, String chatId) {
    public static final String CONTEXT_KEY = "cinema.identity";

    public static AiIdentity from(ToolContext context) {
        if (context == null || !(context.getContext().get(CONTEXT_KEY) instanceof AiIdentity identity)
                || identity.userId() == null || identity.chatId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请登录后使用个人服务");
        }
        return identity;
    }
}
