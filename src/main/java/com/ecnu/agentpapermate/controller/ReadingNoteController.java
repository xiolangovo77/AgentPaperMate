package com.ecnu.agentpapermate.controller;

import com.ecnu.agentpapermate.common.BaseResponse;
import com.ecnu.agentpapermate.common.ResultUtils;
import com.ecnu.agentpapermate.entity.dto.readingnote.ReadingNoteAddRequest;
import com.ecnu.agentpapermate.entity.dto.readingnote.ReadingNoteUpdateRequest;
import com.ecnu.agentpapermate.entity.vo.readingnote.ReadingNoteVO;
import com.ecnu.agentpapermate.service.AppUserService;
import com.ecnu.agentpapermate.service.ReadingNoteService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 阅读笔记控制器。
 */
@RestController
@RequestMapping("/readingNote")
@RequiredArgsConstructor
public class ReadingNoteController {

    private final ReadingNoteService readingNoteService;
    private final AppUserService appUserService;

    /** 为当前用户的论文新增阅读笔记。 */
    @PostMapping("/add")
    public BaseResponse<Long> add(@RequestBody ReadingNoteAddRequest body, HttpServletRequest request) {
        Long userId = appUserService.getLoginUser(request).getId();
        return ResultUtils.success(readingNoteService.addReadingNote(body, userId));
    }

    /** 更新当前用户的论文笔记，返回保存后的版本。 */
    @PostMapping("/update")
    public BaseResponse<Integer> update(@RequestBody ReadingNoteUpdateRequest body, HttpServletRequest request) {
        Long userId = appUserService.getLoginUser(request).getId();
        return ResultUtils.success(readingNoteService.updateReadingNote(body, userId));
    }

    /** 按论文 ID 查询当前用户的阅读笔记。 */
    @GetMapping("/get")
    public BaseResponse<ReadingNoteVO> get(@RequestParam("paperId") Long paperId, HttpServletRequest request) {
        Long userId = appUserService.getLoginUser(request).getId();
        return ResultUtils.success(readingNoteService.getReadingNote(paperId, userId));
    }
}
