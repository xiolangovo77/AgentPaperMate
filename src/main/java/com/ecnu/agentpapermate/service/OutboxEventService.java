package com.ecnu.agentpapermate.service;

import com.ecnu.agentpapermate.entity.model.OutboxEvent;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 * 可靠投递到RabbitMQ的事件 服务类
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
public interface OutboxEventService extends IService<OutboxEvent> {

}
