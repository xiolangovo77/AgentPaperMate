package com.ecnu.agentpapermate.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecnu.agentpapermate.common.BaseResponse;
import com.ecnu.agentpapermate.common.ResultUtils;
import com.ecnu.agentpapermate.entity.dto.sessionPaper.SessionPaperAddRequest;
import com.ecnu.agentpapermate.entity.dto.sessionPaper.SessionPaperQueryRequest;
import com.ecnu.agentpapermate.entity.dto.sessionPaper.SessionPaperUpdateRequest;
import com.ecnu.agentpapermate.entity.model.SessionPaper;
import com.ecnu.agentpapermate.service.AppUserService;
import com.ecnu.agentpapermate.service.SessionPaperService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * <p>
 * 聊天室当前论文关联 前端控制器
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@RestController
@RequestMapping("/sessionPaper")
@RequiredArgsConstructor
public class SessionPaperController {

    private final SessionPaperService sessionPaperService;
    private final AppUserService appUserService;

    /** 添加或恢复论文关联，返回关联ID。 */
    @PostMapping("/add")
    public BaseResponse<Long> add(@RequestBody SessionPaperAddRequest body, HttpServletRequest request) {
        return ResultUtils.success(sessionPaperService.addSessionPaper(body, appUserService.getLoginUser(request).getId()));
    }

    /** 查询当前用户的有效论文关联。 */
    @GetMapping("/get")
    public BaseResponse<SessionPaper> get(@RequestParam Long id, HttpServletRequest request) {
        return ResultUtils.success(sessionPaperService.getSessionPaper(id, appUserService.getLoginUser(request).getId()));
    }

    /** 分页查询聊天室中的有效论文关联。 */
    @PostMapping("/list/page")
    public BaseResponse<Page<SessionPaper>> listPage(@RequestBody SessionPaperQueryRequest body,
                                                    HttpServletRequest request) {
        return ResultUtils.success(sessionPaperService.listSessionPapers(body, appUserService.getLoginUser(request).getId()));
    }

    /** 更新关联状态：0恢复、1移出。 */
    @PostMapping("/update")
    public BaseResponse<Boolean> update(@RequestBody SessionPaperUpdateRequest body, HttpServletRequest request) {
        return ResultUtils.success(sessionPaperService.updateSessionPaper(body, appUserService.getLoginUser(request).getId()));
    }
}
