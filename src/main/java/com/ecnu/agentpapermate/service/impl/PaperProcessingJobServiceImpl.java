package com.ecnu.agentpapermate.service.impl;

import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.ecnu.agentpapermate.mapper.PaperProcessingJobMapper;
import com.ecnu.agentpapermate.service.PaperProcessingJobService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.beans.BeanUtils;
import lombok.RequiredArgsConstructor;
import com.ecnu.agentpapermate.service.PaperService;
import com.ecnu.agentpapermate.entity.vo.paper.PaperProcessingJobVO;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.exception.ThrowUtils;

/**
 * <p>
 * PDF解析与索引任务 服务实现类
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
@Service
@RequiredArgsConstructor
public class PaperProcessingJobServiceImpl extends ServiceImpl<PaperProcessingJobMapper, PaperProcessingJob> implements PaperProcessingJobService {

    /**
     * 处理任务数据访问接口，读取当前状态、步骤和最近错误。
     */
    private final PaperProcessingJobMapper jobMapper;
    /**
     * 论文服务，校验处理任务所属论文的访问权限。
     */
    private final PaperService paperService;

    @Override
    public PaperProcessingJobVO getJob(Long id, Long userId) {
        //1、校验登录态与任务 ID，仅查询未删除任务
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "任务ID不合法");
        PaperProcessingJob job = jobMapper.selectById(id);
        ThrowUtils.throwIf(job == null, ErrorCode.NOT_FOUND_ERROR, "处理任务不存在或无权访问");

        //2、校验论文归属后映射进度字段，禁止返回内部租约和处理配置
        paperService.requireOwnedPaper(job.getPaperId(), userId);
        PaperProcessingJobVO result = new PaperProcessingJobVO();
        BeanUtils.copyProperties(job, result);
        return result;
    }
}
