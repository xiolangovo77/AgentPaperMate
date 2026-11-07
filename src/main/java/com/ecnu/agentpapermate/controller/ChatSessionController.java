package com.ecnu.agentpapermate.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecnu.agentpapermate.common.BaseResponse;
import com.ecnu.agentpapermate.common.DeleteRequest;
import com.ecnu.agentpapermate.common.ResultUtils;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.exception.ThrowUtils;
import com.ecnu.agentpapermate.entity.dto.chatSession.ChatSessionAddRequest;
import com.ecnu.agentpapermate.entity.dto.chatSession.ChatSessionQueryRequest;
import com.ecnu.agentpapermate.entity.dto.chatSession.ChatSessionUpdateRequest;
import com.ecnu.agentpapermate.entity.model.ChatSession;
import com.ecnu.agentpapermate.service.AppUserService;
import com.ecnu.agentpapermate.service.ChatSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * <p>
 * 研究聊天室 前端控制器
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@RestController
@RequestMapping("/chatSession")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ChatSessionService chatSessionService;
    private final AppUserService appUserService;

    /** 创建聊天室，返回聊天室ID。 */
    @PostMapping("/add")
    public BaseResponse<Long> add(@RequestBody ChatSessionAddRequest body, HttpServletRequest request) {
        return ResultUtils.success(chatSessionService.addSession(body, appUserService.getLoginUser(request).getId()));
    }

    /** 查询当前用户的聊天室详情。 */
    @GetMapping("/get")
    public BaseResponse<ChatSession> get(@RequestParam Long id, HttpServletRequest request) {
        return ResultUtils.success(chatSessionService.getSession(id, appUserService.getLoginUser(request).getId()));
    }

    /** 分页查询当前用户的聊天室。 */
    @PostMapping("/list/page")
    public BaseResponse<Page<ChatSession>> listPage(@RequestBody ChatSessionQueryRequest body,
                                                   HttpServletRequest request) {
        return ResultUtils.success(chatSessionService.listSessions(body, appUserService.getLoginUser(request).getId()));
    }

    /** 修改聊天室名称或输入草稿。 */
    @PostMapping("/update")
    public BaseResponse<Boolean> update(@RequestBody ChatSessionUpdateRequest body, HttpServletRequest request) {
        return ResultUtils.success(chatSessionService.updateSession(body, appUserService.getLoginUser(request).getId()));
    }

    /** 逻辑删除无活动任务的聊天室及其关联数据。 */
    @PostMapping("/delete")
    public BaseResponse<Boolean> delete(@RequestBody DeleteRequest body, HttpServletRequest request) {
        Long userId = appUserService.getLoginUser(request).getId();
        ThrowUtils.throwIf(body == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(chatSessionService.deleteSession(body.getId(), userId));
    }
}
