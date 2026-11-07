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

    /** 创建任务时设置活动 Run、清空草稿并更新最近活动时间，调用方持有会话锁。 */
    int assignActiveRun(@Param("sessionId") Long sessionId, @Param("runId") Long runId);

    /** 仅释放仍属于当前 Run 的会话占用，防止清除其他任务。 */
    int releaseActiveRun(@Param("sessionId") Long sessionId, @Param("runId") Long runId);

    // 以下批量删除仅供会话删除事务调用，调用方必须持有已校验归属的会话锁。
    /** 删除会话全部消息的引用，包括已删除消息下残留的引用。 */
    int logicalDeleteCitations(@Param("sessionId") Long sessionId);

    /** 删除会话全部任务的事件，包括已删除任务下残留的事件。 */
    int logicalDeleteRunEvents(@Param("sessionId") Long sessionId);

    /** 删除会话消息，包含不关联 Run 的资料变更消息。 */
    int logicalDeleteMessages(@Param("sessionId") Long sessionId);

    /** 删除会话任务历史。 */
    int logicalDeleteRuns(@Param("sessionId") Long sessionId);

    /** 删除会话论文关联，保留论文。 */
    int logicalDeleteSessionPapers(@Param("sessionId") Long sessionId);

    /** 删除当前用户没有活动任务的有效会话。 */
    int logicalDeleteIdleSession(@Param("id") Long id, @Param("userId") Long userId);
}
