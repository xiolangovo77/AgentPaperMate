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
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecnu.agentpapermate.entity.dto.paper.PaperQueryRequest;
import com.ecnu.agentpapermate.entity.vo.paper.PaperSummaryVO;
import com.ecnu.agentpapermate.entity.vo.paper.PaperDetailVO;
import com.ecnu.agentpapermate.service.PaperFileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

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
     * 论文文件服务，校验访问权限并输出完整 PDF 或指定字节范围。
     */
    private final PaperFileService paperFileService;

    @PostMapping("/retry")
    public BaseResponse<PaperAddVO> retry(
            @RequestBody com.ecnu.agentpapermate.entity.dto.paper.PaperRetryRequest body,
            HttpServletRequest request) {
        Long userId = appUserService.getLoginUser(request).getId();
        return ResultUtils.success(paperService.retryPaper(body.getId(), body.getJobId(), userId));
    }

    @PostMapping("/recycle")
    public BaseResponse<Boolean> recycle(@RequestBody com.ecnu.agentpapermate.entity.dto.paper.PaperRecycleRequest body,
                                        HttpServletRequest request) {
        //1、从登录态获取当前用户，避免客户端指定论文所有者
        Long userId = appUserService.getLoginUser(request).getId();
        //2、调用业务服务回收或恢复论文，统一包装操作结果
        return ResultUtils.success(paperService.setRecycled(body.getId(), body.getIsRecycled(),
                userId));
    }

    @PostMapping("/list/page")
    public BaseResponse<Page<PaperSummaryVO>> listPage(@RequestBody PaperQueryRequest body, HttpServletRequest request) {
        //1、从登录态读取用户身份，分页查询本人论文
        Long userId = appUserService.getLoginUser(request).getId();
        //2、统一包装分页记录与总数
        return ResultUtils.success(paperService.listPapers(body, userId));
    }

    @GetMapping("/get")
    public BaseResponse<PaperDetailVO> get(@RequestParam Long id, HttpServletRequest request) {
        //1、从登录态读取用户身份并校验论文访问权限
        Long userId = appUserService.getLoginUser(request).getId();
        //2、返回论文详情、问答可用性及处理错误
        return ResultUtils.success(paperService.getPaper(id, userId));
    }

    @GetMapping("/file")
    public void file(@RequestParam Long id, @RequestHeader(value = "Range", required = false) String range,
                     @RequestHeader(value = "If-Range", required = false) String ifRange,
                     HttpServletRequest request, HttpServletResponse response) throws IOException {
        //1、读取登录用户，未登录请求不能进入存储读取流程
        Long userId = appUserService.getLoginUser(request).getId();
        //2、直接输出文件流及 HTTP 范围响应头，不包装为 JSON
        paperFileService.writeFile(id, userId, range, ifRange, response);
    }

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
