package com.ecnu.agentpapermate.service;

import com.ecnu.agentpapermate.entity.model.Paper;
import com.baomidou.mybatisplus.spring.service.IService;
import com.ecnu.agentpapermate.entity.dto.paper.PaperAddRequest;
import com.ecnu.agentpapermate.entity.vo.paper.PaperAddVO;

/**
 * <p>
 * 论文及原始文件信息 服务类
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
public interface PaperService extends IService<Paper> {

    /**
     * 保存论文，并创建论文处理任务和待投递事件。
     *
     * @param request 论文保存请求
     * @param userId  当前登录用户 ID
     * @return 论文保存结果
     */
    PaperAddVO addPaper(PaperAddRequest request, Long userId);
}
