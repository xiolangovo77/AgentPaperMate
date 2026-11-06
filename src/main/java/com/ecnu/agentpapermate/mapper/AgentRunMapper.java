package com.ecnu.agentpapermate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecnu.agentpapermate.entity.model.AgentRun;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 单次问答执行任务 Mapper 接口
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {

}
