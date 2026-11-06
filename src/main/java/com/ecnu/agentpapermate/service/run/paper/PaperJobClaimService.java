package com.ecnu.agentpapermate.service.run.paper;

import com.ecnu.agentpapermate.config.paper.PaperJobSchedulerProperties;
import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.ecnu.agentpapermate.mapper.PaperProcessingJobMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import com.ecnu.agentpapermate.ingestion.model.ParsedPaper;

/**
 * 所有行锁只在短事务内持有，事务提交后才允许执行外部工作。
 */
@Service
public class PaperJobClaimService {
    /**
     * 论文处理任务的数据访问接口，负责状态更新及租约条件校验
     */
    private final PaperProcessingJobMapper mapper;
    /**
     * 任务领取、租约回收和失败重试所需的调度参数
     */
    private final PaperJobSchedulerProperties properties;
    /**
     * 独立短事务模板，使用读已提交隔离级别并限制事务超时
     */
    private final TransactionTemplate transaction;

    /**
     * 初始化任务服务及短事务模板，避免外部调用长时间占用数据库行锁
     */
    public PaperJobClaimService(PaperProcessingJobMapper mapper, PaperJobSchedulerProperties properties,
                                PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setTimeout(10);
    }

    /**
     * 在事务中锁定并领取一条到期任务，生成新租约令牌；无可领取任务时返回 null
     */
    public PaperProcessingJob claimNext() {
        return transaction.execute(status -> {
            PaperProcessingJob job = mapper.selectDueForUpdate();
            if (job == null) {
                return null;
            }
            String token = UUID.randomUUID().toString();
            if (mapper.claim(job.getId(), token, properties.getLeaseSeconds()) != 1) {
                throw new IllegalStateException("锁定任务后领取失败: " + job.getId());
            }
            return mapper.selectById(job.getId());
        });
    }

    /**
     * 批量回收过期租约，安排重试或标记失败，并返回成功更新的任务数
     */
    public int recoverExpired() {
        Integer count = transaction.execute(status -> {
            int recovered = 0;
            for (PaperProcessingJob job : mapper.selectExpiredForUpdate(properties.getBatchSize())) {
                recovered += mapper.retryOrFail(job.getId(), job.getLeaseToken(), true,
                        properties.getRetryDelaySeconds(), "LEASE_EXPIRED");
                mapper.markPaperFailed(job.getId());
            }
            return recovered;
        });
        return count == null ? 0 : count;
    }

    /**
     * 在当前租约仍有效时安排延迟重试或标记任务失败，按条件同步论文失败状态
     */
    public void retryOrFail(PaperProcessingJob job, String errorCode) {
        transaction.executeWithoutResult(status -> {
            if (mapper.retryOrFail(job.getId(), job.getLeaseToken(), false, properties.getRetryDelaySeconds(), errorCode) > 0) {
                mapper.markPaperFailed(job.getId());
            }
        });
    }

    /**
     * 按配置延长任务租约，续期条件不满足时返回 false
     */
    public boolean renew(PaperProcessingJob job, int maxSeconds) {
        return mapper.renew(job, properties.getLeaseSeconds(), maxSeconds) > 0;
    }

    /**
     * 在独立事务中保存解析元数据，租约或版本校验未通过时抛出异常
     */
    public void updateParsedMetadata(PaperProcessingJob job, ParsedPaper parsed) {
        transaction.executeWithoutResult(status -> {
            if (mapper.updateParsedMetadata(job, parsed) == 0) throw new IllegalStateException("元数据更新被租约或版本检查拒绝");
        });
    }

    /**
     * 更新处理步骤和分块进度，任务租约失效或论文已删除时抛出异常
     */
    public void progress(PaperProcessingJob job, String step, int total, int indexed) {
        if (mapper.progress(job, step, total, indexed) == 0) throw new IllegalStateException("任务已失去租约或论文已删除");
    }

    /**
     * 在独立事务中提交任务完成状态并发布论文索引，条件校验未通过时抛出异常
     */
    public void complete(PaperProcessingJob job, ParsedPaper parsed, int total, int maxSeconds) {
        transaction.executeWithoutResult(status -> {
            if (mapper.complete(job, parsed, total, maxSeconds) == 0) throw new IllegalStateException("任务完成提交被租约或版本检查拒绝");
        });
    }
}
