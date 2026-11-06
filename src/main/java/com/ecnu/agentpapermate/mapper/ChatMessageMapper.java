package com.ecnu.agentpapermate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecnu.agentpapermate.entity.model.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 聊天室消息 Mapper 接口
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

}
