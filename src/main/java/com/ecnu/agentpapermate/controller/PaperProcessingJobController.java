package com.ecnu.agentpapermate.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import com.ecnu.agentpapermate.common.BaseResponse;
import com.ecnu.agentpapermate.common.ResultUtils;
import com.ecnu.agentpapermate.entity.vo.paper.PaperProcessingJobVO;
import com.ecnu.agentpapermate.service.AppUserService;
import com.ecnu.agentpapermate.service.PaperProcessingJobService;

/**
 * <p>
 * PDF解析与索引任务 前端控制器
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/paperProcessingJob")
@RequiredArgsConstructor
public class PaperProcessingJobController {
    /**
     * 论文任务查询服务，校验归属并读取处理状态、步骤和错误。
     */
    private final PaperProcessingJobService jobService;
    /**
     * 用户服务，从请求登录态获取当前用户身份。
     */
    private final AppUserService appUserService;

    @GetMapping("/get")
    public BaseResponse<PaperProcessingJobVO> get(@RequestParam Long id, HttpServletRequest request) {
        //1、从登录态取得用户身份，禁止信任请求传入的用户 ID
        Long userId = appUserService.getLoginUser(request).getId();
        //2、校验论文归属并返回可公开的任务进度
        return ResultUtils.success(jobService.getJob(id, userId));
    }
}
