package com.ecnu.agentpapermate.service.impl;

import com.ecnu.agentpapermate.entity.model.ChatMessage;
import com.ecnu.agentpapermate.mapper.ChatMessageMapper;
import com.ecnu.agentpapermate.service.ChatMessageService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 聊天室消息 服务实现类
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageService {

}
