package com.ecnu.agentpapermate.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.ecnu.agentpapermate.common.BaseResponse;
import com.ecnu.agentpapermate.common.ResultUtils;
import com.ecnu.agentpapermate.entity.model.MessageCitation;
import com.ecnu.agentpapermate.service.run.rag.RunQueries;
import com.ecnu.agentpapermate.service.AppUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.util.List;

/**
 * <p>
 * 回答原文引用快照 前端控制器
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@RestController
@RequestMapping("/messageCitation")
@RequiredArgsConstructor
public class MessageCitationController {
    /**
     * 引用查询服务，校验消息所属会话并返回引用快照。
     */
    private final RunQueries queries;
    /**
     * 用户业务服务，从当前请求读取并校验登录用户。
     */
    private final AppUserService users;

    /** 查询当前用户可访问消息的引用快照。 */
    @GetMapping("/list")
    public BaseResponse<List<MessageCitation>> list(@RequestParam Long messageId, HttpServletRequest request) {
        return ResultUtils.success(queries.citations(messageId, users.getLoginUser(request).getId()));
    }

}
