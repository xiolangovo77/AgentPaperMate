package com.ecnu.agentpapermate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecnu.agentpapermate.entity.model.OutboxEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 可靠投递到RabbitMQ的事件 Mapper 接口
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

}
