package com.ecnu.agentpapermate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecnu.agentpapermate.entity.model.AgentRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 单次问答执行任务 Mapper 接口
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {
    /** 调用前先锁定所属聊天室，统一锁顺序，防止取消与完成互相覆盖。 */
    AgentRun selectForUpdate(@Param("id") Long id);

    /** 查询运行或排队超时的候选任务，状态最终在事务锁内重新确认。 */
    List<AgentRun> selectExpiredCandidates(@Param("now") LocalDateTime now,
                                         @Param("queuedBefore") LocalDateTime queuedBefore,
                                         @Param("limit") int limit);

    /** 按任务 ID 顺序查询待领取任务，限制单轮候选数量。 */
    List<AgentRun> selectPendingCandidates(@Param("limit") int limit);
}
