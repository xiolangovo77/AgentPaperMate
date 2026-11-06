package com.ecnu.agentpapermate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecnu.agentpapermate.entity.model.RunEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 问答运行进度及SSE补发事件 Mapper 接口
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Mapper
public interface RunEventMapper extends BaseMapper<RunEvent> {

}
