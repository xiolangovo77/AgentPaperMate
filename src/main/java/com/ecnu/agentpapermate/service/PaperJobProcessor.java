package com.ecnu.agentpapermate.service;

import com.ecnu.agentpapermate.config.paper.PaperIngestionProperties;
import com.ecnu.agentpapermate.config.paper.PaperJobSchedulerProperties;
import com.ecnu.agentpapermate.config.paper.PaperProcessingProperties;
import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.ecnu.agentpapermate.ingestion.*;
import com.ecnu.agentpapermate.mange.CosManager;
import com.ecnu.agentpapermate.mapper.PaperMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 执行单次论文入库任务，串联下载、解析、分块、向量化、索引验证及完成提交
 */
@Component
public class PaperJobProcessor {
    /**
     * 入库流水线开关、超时、心跳及向量化批次配置
     */
    private final PaperIngestionProperties properties;
    /**
     * 当前执行器的解析、分块和向量模型配置，用于校验任务快照兼容性
     */
    private final PaperProcessingProperties processing;
    /**
     * 论文数据访问接口，用于读取待处理论文
     */
    private final PaperMapper papers;
    /**
     * 对象存储管理器，用于下载原始 PDF 并获取文件校验信息
     */
    private final CosManager cos;
    /**
     * PDF 解析客户端，负责获取结构化论文内容
     */
    private final GrobidClient grobid;
    /**
     * 论文分块器，负责生成带来源元数据的索引文档
     */
    private final PaperChunker chunker;
    /**
     * 向量索引写入器，负责索引准备、分批写入和数量验证
     */
    private final PaperVectorWriter vectors;
    /**
     * 任务状态服务，负责租约续期、进度更新及完成提交
     */
    private final PaperJobClaimService jobs;
    /**
     * 租约心跳调度线程池，在任务执行期间定期触发续期
     */
    private final ScheduledExecutorService heartbeat;

    /**
     * 注入入库依赖，并校验心跳与租约时长关系及启用时的处理配置
     */
    public PaperJobProcessor(PaperIngestionProperties properties, PaperProcessingProperties processing,
                             PaperJobSchedulerProperties scheduler, PaperMapper papers, CosManager cos,
                             GrobidClient grobid, PaperChunker chunker, PaperVectorWriter vectors,
                             PaperJobClaimService jobs,
                             @Qualifier("paperLeaseHeartbeatExecutor") ScheduledExecutorService heartbeat) {
        // 1、检查租约时长是否给心跳续期留足时间
        if (properties.getHeartbeatSeconds() * 2 >= scheduler.getLeaseSeconds()) {
            throw new IllegalArgumentException("租约时长必须大于心跳间隔的两倍");
        }
        // 2、启用入库时校验解析器和向量模型的版本配置
        if (properties.isEnabled() && (processing.getParserVersion().contains("unconfigured")
                || processing.getEmbeddingModel().startsWith("placeholder")
                || processing.getEmbeddingVersion().startsWith("placeholder"))) {
            throw new IllegalArgumentException("开启入库前请填写真实解析器版本、Embedding 模型和版本");
        }
        this.properties = properties;
        this.processing = processing;
        this.papers = papers;
        this.cos = cos;
        this.grobid = grobid;
        this.chunker = chunker;
        this.vectors = vectors;
        this.jobs = jobs;
        this.heartbeat = heartbeat;
    }

    /**
     * 返回论文入库流水线是否启用
     */
    public boolean isEnabled() { return properties.isEnabled(); }

    /**
     * 在租约保护下执行完整入库流程，确认分块可检索后提交完成状态，并最终清理临时 PDF
     */
    public void process(PaperProcessingJob job) {
        // 1、检查入库开关，核对任务快照与当前执行器的配置
        if (!isEnabled()) throw new IllegalStateException("论文入库未启用");
        requireCompatible(job);
        Path pdf = null;
        // 2、启动租约心跳，读取本次任务的论文记录
        try (var lease = new PaperLease(job, jobs, properties, heartbeat)) {
            var paper = papers.selectById(job.getPaperId());
            if (paper == null) throw new IllegalStateException("论文不存在或已删除");
            // 3、下载原始 PDF 到临时文件，用上传时保存的哈希校验内容
            jobs.progress(job, "parsing", 0, 0);
            pdf = Files.createTempFile("paper-" + job.getId() + "-", ".pdf");
            var file = cos.downloadPaper(paper.getFileUrl(), paper.getUserId(), pdf);
            if (!Objects.equals(file.getFileHash(), paper.getFileHash())) throw new IllegalStateException("原始 PDF 内容发生变化");
            // 4、在解析前后检查租约，再保存 GROBID 提取的元数据
            lease.check();
            var parsed = grobid.parse(pdf);
            lease.check();
            jobs.updateParsedMetadata(job, parsed);
            if (!parsed.title().isBlank()) paper.setTitle(parsed.title());
            // 5、按论文结构分块，并确认目标索引与任务配置兼容
            jobs.progress(job, "chunking", 0, 0);
            var documents = chunker.split(parsed, paper, job);
            lease.check();
            vectors.ensureIndex(job);
            // 6、分批写入向量，每批写入前后检查租约并记录进度
            for (int start = 0; start < documents.size(); start += properties.getEmbeddingBatchSize()) {
                lease.check();
                int end = Math.min(start + properties.getEmbeddingBatchSize(), documents.size());
                jobs.progress(job, "embedding", documents.size(), start);
                vectors.write(job, documents.subList(start, end));
                lease.check();
                jobs.progress(job, "indexing", documents.size(), end);
            }
            // 7、核对本次写入的分块数量，再凭有效租约提交完成状态
            jobs.progress(job, "verifying", documents.size(), documents.size());
            lease.check();
            vectors.verify(job, documents.size());
            lease.check();
            jobs.complete(job, parsed, documents.size(), properties.getMaxDurationSeconds());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("论文临时文件操作失败", e);
        } finally {
            // 8、清理临时 PDF，删除失败时登记为进程退出时清理
            if (pdf != null) {
                try { Files.deleteIfExists(pdf); }
                catch (java.io.IOException ignored) { pdf.toFile().deleteOnExit(); }
            }
        }
    }

    /**
     * 校验任务配置快照与当前执行器的模型、维度、解析器及分块版本一致
     */
    private void requireCompatible(PaperProcessingJob job) {
        if (!Objects.equals(job.getEmbeddingModel(), processing.getEmbeddingModel())
                || !Objects.equals(job.getEmbeddingVersion(), processing.getEmbeddingVersion())
                || !Objects.equals(job.getEmbeddingDimension(), processing.getEmbeddingDimension())
                || !Objects.equals(job.getParserVersion(), processing.getParserVersion())
                || !Objects.equals(job.getChunkingVersion(), processing.getChunkingVersion())) {
            throw new IllegalStateException("任务配置快照与当前执行器不兼容，请重新创建处理版本");
        }
    }
}
