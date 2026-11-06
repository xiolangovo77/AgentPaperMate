package com.ecnu.agentpapermate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.ecnu.agentpapermate.ingestion.model.ParsedPaper;

/**
 * <p>
 * PDF解析与索引任务 Mapper 接口
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
@Mapper
public interface PaperProcessingJobMapper extends BaseMapper<PaperProcessingJob> {
    /**
     * 查询并锁定一条已到执行时间的待处理任务（需在事务中调用，跳过已锁定任务）
     */
    PaperProcessingJob selectDueForUpdate();

    /**
     * 领取待处理任务，设置运行状态、租约令牌和租约有效期
     */
    int claim(@Param("id") Long id, @Param("token") String token, @Param("leaseSeconds") int leaseSeconds);

    /**
     * 按数量上限查询并锁定租约已过期的运行中任务（需在事务中调用，跳过已锁定任务）
     */
    List<PaperProcessingJob> selectExpiredForUpdate(@Param("limit") int limit);

    /**
     * 校验租约令牌及过期状态后安排延迟重试，达到重试上限则标记失败，并清除租约
     */
    int retryOrFail(@Param("id") Long id, @Param("token") String token,
                    @Param("expired") boolean expired, @Param("delaySeconds") int delaySeconds,
                    @Param("errorCode") String errorCode);

    /**
     * 为租约有效且未超过最大执行时长的运行中任务续租
     */
    int renew(@Param("job") PaperProcessingJob job, @Param("leaseSeconds") int leaseSeconds, @Param("maxSeconds") int maxSeconds);

    /**
     * 在租约有效时更新处理步骤和分块进度，论文尚无可用索引时同步标记为处理中
     */
    int progress(@Param("job") PaperProcessingJob job, @Param("step") String step, @Param("total") int total, @Param("indexed") int indexed);

    /**
     * 校验租约、执行时长及索引版本后完成任务，同步发布论文索引并标记为就绪
     */
    int complete(@Param("job") PaperProcessingJob job, @Param("parsed") ParsedPaper parsed, @Param("total") int total, @Param("maxSeconds") int maxSeconds);

    /**
     * 根据任务 ID 将对应论文标记为失败（任务已失败、论文无可用索引且无更新版本任务时生效）
     */
    int markPaperFailed(@Param("id") Long id);

    /**
     * 校验租约及索引版本后保存论文解析元数据，并将任务推进至分块阶段
     */
    int updateParsedMetadata(@Param("job") PaperProcessingJob job, @Param("parsed") ParsedPaper parsed);
}
