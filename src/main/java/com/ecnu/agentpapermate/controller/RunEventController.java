package com.ecnu.agentpapermate.controller;

import com.ecnu.agentpapermate.entity.vo.run.EventView;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.ecnu.agentpapermate.common.BaseResponse;
import com.ecnu.agentpapermate.common.ResultUtils;
import com.ecnu.agentpapermate.service.run.rag.RunQueries;
import com.ecnu.agentpapermate.service.AppUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.util.List;

/**
 * <p>
 * 问答运行进度及SSE补发事件 前端控制器
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@RestController
@RequestMapping("/runEvent")
@RequiredArgsConstructor
public class RunEventController {
    /**
     * 运行事件查询服务，校验任务归属并按事件序号读取进度记录。
     */
    private final RunQueries queries;
    /**
     * 用户业务服务，从当前请求读取并校验登录用户。
     */
    private final AppUserService users;

    /** 按事件序号读取已提交事件，可用于不使用 SSE 的客户端。 */
    @GetMapping("/list")
    public BaseResponse<List<EventView>> list(@RequestParam Long runId,
            @RequestParam(defaultValue = "0") Long afterEventSeq, HttpServletRequest request) {
        return ResultUtils.success(queries.events(runId, afterEventSeq, users.getLoginUser(request).getId()));
    }

}
