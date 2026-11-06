package com.ecnu.agentpapermate.service.impl;

import com.ecnu.agentpapermate.entity.model.RunEvent;
import com.ecnu.agentpapermate.mapper.RunEventMapper;
import com.ecnu.agentpapermate.service.RunEventService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 问答运行进度及SSE补发事件 服务实现类
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Service
public class RunEventServiceImpl extends ServiceImpl<RunEventMapper, RunEvent> implements RunEventService {

}
