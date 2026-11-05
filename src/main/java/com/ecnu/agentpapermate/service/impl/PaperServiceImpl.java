package com.ecnu.agentpapermate.service.impl;

import com.ecnu.agentpapermate.entity.model.Paper;
import com.ecnu.agentpapermate.mapper.PaperMapper;
import com.ecnu.agentpapermate.service.PaperService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ecnu.agentpapermate.config.paper.PaperProcessingProperties;
import com.ecnu.agentpapermate.entity.dto.paper.PaperAddRequest;
import com.ecnu.agentpapermate.entity.model.PaperProcessingJob;
import com.ecnu.agentpapermate.entity.vo.paper.PaperAddVO;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.exception.ThrowUtils;
import com.ecnu.agentpapermate.mange.CosManager;
import com.ecnu.agentpapermate.mapper.PaperProcessingJobMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * <p>
 * 论文及原始文件信息 服务实现类
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-03
 */
@Service
@RequiredArgsConstructor
public class PaperServiceImpl extends ServiceImpl<PaperMapper, Paper> implements PaperService {

    /**
     * 论文数据访问接口。
     */
    private final PaperMapper paperMapper;

    /**
     * 论文处理任务数据访问接口。
     */
    private final PaperProcessingJobMapper jobMapper;

    /**
     * COS 文件管理组件。
     */
    private final CosManager cosManager;

    /**
     * 论文处理配置，创建任务时复制为配置快照。
     */
    private final PaperProcessingProperties processingProperties;

    /**
     * 任务配置的 JSON 序列化工具。
     */
    private final ObjectMapper objectMapper;

    /**
     * 数据库事务模板，确保论文和任务同时保存。
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 保存论文，并在同一事务内创建处理任务。
     *
     * @param request 论文保存请求
     * @param userId  从登录态取得的当前用户 ID
     * @return 论文保存结果，重复提交时返回已有记录
     */
    @Override
    public PaperAddVO addPaper(PaperAddRequest request, Long userId) {
        // 1、校验请求参数和用户身份
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR);

        // 2、读取 COS 文件并计算 SHA-256，不占用数据库事务
        CosManager.PaperFileInfo file = cosManager.readPaper(request.getFileUrl(), userId);
        try {
            return transactionTemplate.execute(tx -> {
                // 3、检查当前用户是否已保存相同内容的论文
                PaperAddVO existing = findExisting(userId, file.getFileHash());
                if (existing != null) {
                    return existing;
                }
                // 4、使用可信文件信息创建论文，时间统一保存 UTC
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                Paper paper = new Paper();
                paper.setUserId(userId);
                paper.setTitle(file.getOriginalFilename());
                paper.setAuthors("");
                paper.setTopic("");
                paper.setOriginalFilename(file.getOriginalFilename());
                paper.setFileHash(file.getFileHash());
                paper.setFileSize(file.getFileSize());
                paper.setFileUrl(request.getFileUrl());
                paper.setStatus("uploaded");
                paper.setCreateTime(now);
                paper.setUpdateTime(now);
                paper.setIsDelete((byte) 0);
                requireInserted(paperMapper.insert(paper));

                // 5、创建待执行任务，任一步失败都回滚
                PaperProcessingJob job = buildJob(paper.getId(), now);
                requireInserted(jobMapper.insert(job));
                return buildPaperAddVO(paper, job);
            });
        } catch (DuplicateKeyException e) {
            // 并发保存相同文件：唯一索引兜底，失败事务回滚后再查已提交的结果。
            PaperAddVO existing = findExisting(userId, file.getFileHash());
            if (existing != null) {
                return existing;
            }
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "论文记录冲突，请检查是否已删除或稍后重试");
        }
    }

    /**
     * 根据用户 ID 和文件哈希查找已有论文及其首次处理任务，不存在时返回 null。
     */
    private PaperAddVO findExisting(Long userId, String hash) {
        Paper paper = paperMapper.selectOne(new QueryWrapper<Paper>()
                .eq("user_id", userId).eq("file_hash", hash));
        if (paper == null) {
            return null;
        }
        PaperProcessingJob job = jobMapper.selectOne(new QueryWrapper<PaperProcessingJob>()
                .eq("paper_id", paper.getId()).eq("index_version", 1));
        ThrowUtils.throwIf(job == null, ErrorCode.OPERATION_ERROR, "论文已存在但处理任务缺失，请联系管理员");
        return buildPaperAddVO(paper, job);
    }

    /**
     * 构建对外返回的论文保存结果。
     *
     * @param paper 论文信息
     * @param job   论文处理任务
     * @return 论文 ID、任务 ID 和论文状态
     */
    private PaperAddVO buildPaperAddVO(Paper paper, PaperProcessingJob job) {
        PaperAddVO result = new PaperAddVO();
        result.setPaperId(paper.getId());
        result.setJobId(job.getId());
        result.setStatus(paper.getStatus());
        return result;
    }

    /**
     * 构建首次处理任务，保存当前处理配置，初始状态为 pending、步骤为 queued。
     */
    private PaperProcessingJob buildJob(Long paperId, LocalDateTime now) {
        PaperProcessingJob job = new PaperProcessingJob();
        job.setPaperId(paperId);
        job.setIndexVersion(1);
        job.setStatus("pending");
        job.setCurrentStep("queued");
        job.setParserVersion(processingProperties.getParserVersion());
        job.setChunkingVersion(processingProperties.getChunkingVersion());
        job.setChunkingConfig(json(Map.of("chunkSize", processingProperties.getChunkSize(),
                "chunkOverlap", processingProperties.getChunkOverlap(), "unit", processingProperties.getChunkUnit(), "tokenizer", "cl100k_base_estimate", "maxChunkSize", processingProperties.getMaxChunkSize())));
        job.setEmbeddingModel(processingProperties.getEmbeddingModel());
        job.setEmbeddingVersion(processingProperties.getEmbeddingVersion());
        job.setEmbeddingDimension(processingProperties.getEmbeddingDimension());
        job.setEsIndexName(processingProperties.getEsIndexName());
        job.setTotalChunks(0);
        job.setIndexedChunks(0);
        job.setRetryCount(0);
        job.setMaxRetries(processingProperties.getMaxRetries());
        job.setNextRetryTime(now);
        job.setCreateTime(now);
        job.setUpdateTime(now);
        job.setIsDelete((byte) 0);
        return job;
    }

    /**
     * 将处理配置序列化为 JSON。
     */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "任务配置序列化失败");
        }
    }

    /**
     * 确认单条记录插入成功，失败时抛出异常触发事务回滚。
     */
    private void requireInserted(int rows) {
        ThrowUtils.throwIf(rows != 1, ErrorCode.OPERATION_ERROR, "保存论文任务失败，请重试");
    }
}
