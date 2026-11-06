package com.ecnu.agentpapermate.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecnu.agentpapermate.common.BaseResponse;
import com.ecnu.agentpapermate.common.ResultUtils;
import com.ecnu.agentpapermate.entity.model.ChatMessage;
import com.ecnu.agentpapermate.service.run.rag.RunQueries;
import com.ecnu.agentpapermate.service.AppUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * <p>
 * 聊天室消息 前端控制器
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@RestController
@RequestMapping("/chatMessage")
@RequiredArgsConstructor
public class ChatMessageController {
    /**
     * 会话消息查询服务，校验访问权限并分页读取聊天和资料变更消息。
     */
    private final RunQueries queries;
    /**
     * 用户业务服务，从当前请求读取并校验登录用户。
     */
    private final AppUserService users;

    /** 倒序分页查询用户消息、助手消息及资料变更消息。 */
    @GetMapping("/list/page")
    public BaseResponse<Page<ChatMessage>> list(@RequestParam Long sessionId,
            @RequestParam(defaultValue = "1") long pageNum, @RequestParam(defaultValue = "20") long pageSize,
            HttpServletRequest request) {
        return ResultUtils.success(queries.messages(sessionId, pageNum, pageSize, users.getLoginUser(request).getId()));
    }

}
