package com.ecnu.agentpapermate.service.impl;

import com.ecnu.agentpapermate.entity.model.OutboxEvent;
import com.ecnu.agentpapermate.mapper.OutboxEventMapper;
import com.ecnu.agentpapermate.service.OutboxEventService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 可靠投递到RabbitMQ的事件 服务实现类
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
@Service
public class OutboxEventServiceImpl extends ServiceImpl<OutboxEventMapper, OutboxEvent> implements OutboxEventService {

}
