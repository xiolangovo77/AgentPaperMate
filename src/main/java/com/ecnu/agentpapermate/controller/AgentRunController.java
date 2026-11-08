package com.ecnu.agentpapermate.controller;

import com.ecnu.agentpapermate.entity.vo.run.RunView;
import com.ecnu.agentpapermate.entity.vo.run.RunEvaluationView;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.ecnu.agentpapermate.common.*;
import com.ecnu.agentpapermate.entity.dto.run.RunCreateRequest;
import com.ecnu.agentpapermate.service.run.rag.RunEventStream;
import com.ecnu.agentpapermate.service.AgentRunService;
import com.ecnu.agentpapermate.service.AppUserService;
import com.ecnu.agentpapermate.exception.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import java.util.UUID;

/**
 * <p>
 * 单次问答执行任务 前端控制器
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@RestController
@RequestMapping("/agentRun")
@RequiredArgsConstructor
public class AgentRunController {
    /**
     * 问答任务业务服务，处理幂等提交、状态查询及主动取消。
     */
    private final AgentRunService runs;
    /**
     * 用户业务服务，从当前请求读取并校验登录用户。
     */
    private final AppUserService users;
    /**
     * 运行事件订阅服务，按客户端游标提供 SSE 实时事件和断线补发。
     */
    private final RunEventStream stream;

    /** 获取新提交的幂等键；客户端也可以自行生成 UUID。 */
    @GetMapping("/request-key")
    public BaseResponse<String> requestKey(HttpServletRequest request, HttpServletResponse response) {
        //1、幂等键不是身份凭证，获取与使用时都需要验证登录态
        users.getLoginUser(request);
        response.setHeader("Cache-Control", "no-store");
        return ResultUtils.success(UUID.randomUUID().toString());
    }

    /** 提交问题，返回 runId；相同幂等请求返回已有 ID。 */
    @PostMapping("/add")
    public BaseResponse<Long> add(@RequestBody RunCreateRequest body, HttpServletRequest request) {
        return ResultUtils.success(runs.createRun(body, users.getLoginUser(request).getId()));
    }

    /** 查询状态、错误、最终答案及引用。 */
    @GetMapping("/get")
    public BaseResponse<RunView> get(@RequestParam Long id, HttpServletRequest request) {
        return ResultUtils.success(runs.getRun(id, users.getLoginUser(request).getId()));
    }

    /** 查询本人的任务评测数据；只读，不触发检索、生成或裁判评分。 */
    @GetMapping("/evaluation")
    public BaseResponse<RunEvaluationView> evaluation(@RequestParam Long id, HttpServletRequest request,
                                                     HttpServletResponse response) {
        Long userId = users.getLoginUser(request).getId();
        response.setHeader("Cache-Control", "no-store");
        return ResultUtils.success(runs.getEvaluation(id, userId));
    }

    /** 用户明确停止生成，重复取消返回已有终态。 */
    @PostMapping("/cancel")
    public BaseResponse<String> cancel(@RequestBody DeleteRequest body, HttpServletRequest request) {
        return ResultUtils.success(runs.cancelRun(body.getId(), users.getLoginUser(request).getId()));
    }

    /** 按序补发事件，EventSource 自动重连时优先使用 Last-Event-ID。 */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestParam Long id, @RequestParam(defaultValue = "0") Long afterEventSeq,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             HttpServletRequest request, HttpServletResponse response) {
        //1、解析重连游标，禁止客户端伪造负数和非法格式
        Long userId = users.getLoginUser(request).getId();
        long cursor = afterEventSeq;
        if (lastEventId != null && !lastEventId.isBlank()) {
            try { cursor = Long.parseLong(lastEventId); }
            catch (NumberFormatException e) { throw new BusinessException(ErrorCode.PARAMS_ERROR, "事件游标不合法"); }
        }
        //2、关闭代理缓存和缓冲，订阅只读取已持久化事件
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return stream.subscribe(id, cursor, userId);
    }

}
