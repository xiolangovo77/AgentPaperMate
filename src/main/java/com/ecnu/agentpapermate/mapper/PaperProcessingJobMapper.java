package com.ecnu.agentpapermate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * PDF解析与索引任务 Mapper 接口
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
@Mapper
public interface PaperProcessingJobMapper extends BaseMapper<PaperProcessingJob> {

}
