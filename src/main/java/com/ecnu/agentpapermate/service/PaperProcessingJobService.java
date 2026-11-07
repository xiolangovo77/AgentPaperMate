package com.ecnu.agentpapermate.service;

import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.baomidou.mybatisplus.spring.service.IService;
import com.ecnu.agentpapermate.entity.vo.paper.PaperProcessingJobVO;

/**
 * <p>
 * PDF解析与索引任务 服务类
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
public interface PaperProcessingJobService extends IService<PaperProcessingJob> {
    /** 校验所属论文访问权限后返回处理进度。 */
    PaperProcessingJobVO getJob(Long id, Long userId);
}
