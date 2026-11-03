package com.ecnu.agentpapermate.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.ecnu.agentpapermate.common.BaseResponse;
import com.ecnu.agentpapermate.common.ResultUtils;
import com.ecnu.agentpapermate.entity.dto.paper.PaperAddRequest;
import com.ecnu.agentpapermate.entity.vo.paper.PaperAddVO;
import com.ecnu.agentpapermate.service.AppUserService;
import com.ecnu.agentpapermate.service.PaperService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * <p>
 * 论文及原始文件信息 前端控制器
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
@RestController
@RequestMapping("/paper")
@RequiredArgsConstructor
public class PaperController {

    /**
     * 论文服务。
     */
    private final PaperService paperService;

    /**
     * 用户服务，用于获取当前登录用户。
     */
    private final AppUserService appUserService;

    /**
     * 保存论文并创建等待执行的入库任务。
     *
     * @param body    论文保存请求
     * @param request HTTP 请求，用于获取登录态
     * @return 论文 ID、处理任务 ID 和论文状态
     */
    @PostMapping("/add")
    public BaseResponse<PaperAddVO> add(@RequestBody PaperAddRequest body, HttpServletRequest request) {
        Long userId = appUserService.getLoginUser(request).getId();
        return ResultUtils.success(paperService.addPaper(body, userId));
    }
}
