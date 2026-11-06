package com.ecnu.agentpapermate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecnu.agentpapermate.entity.model.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 研究聊天室 Mapper 接口
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
    /** 锁定用户的有效聊天室，串行处理同一会话的论文集合变更。 */
    ChatSession selectOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    /** 论文集合实际变化后递增版本并更新最近活动时间。 */
    int incrementScopeVersion(@Param("id") Long id, @Param("userId") Long userId);
}
