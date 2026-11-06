package com.ecnu.agentpapermate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecnu.agentpapermate.entity.model.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

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
    /** 查询当前任务之前的用户问题，按消息 ID 倒序截取历史。 */
    List<ChatMessage> selectPreviousQuestions(@Param("sessionId") Long sessionId,
                                             @Param("beforeRunId") Long beforeRunId,
                                             @Param("limit") int limit);
}
