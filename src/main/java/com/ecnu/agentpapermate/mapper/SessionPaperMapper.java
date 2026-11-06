package com.ecnu.agentpapermate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecnu.agentpapermate.entity.model.SessionPaper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 聊天室当前论文关联 Mapper 接口
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Mapper
public interface SessionPaperMapper extends BaseMapper<SessionPaper> {
    /** 查询关联（含已移出），用于恢复原记录。 */
    SessionPaper selectIncludingRemoved(@Param("id") Long id);

    /** 锁定指定关联（含已移出），调用前先锁定所属聊天室。 */
    SessionPaper selectPairForUpdate(@Param("sessionId") Long sessionId, @Param("paperId") Long paperId);

    /** 显式更新逻辑删除状态，支持恢复已移出的关联。 */
    int updateMembership(@Param("id") Long id, @Param("isDelete") byte isDelete);
}
