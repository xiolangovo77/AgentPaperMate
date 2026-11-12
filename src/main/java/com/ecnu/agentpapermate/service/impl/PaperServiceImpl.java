package com.ecnu.agentpapermate.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.BeanUtils;
import org.springframework.util.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecnu.agentpapermate.entity.dto.paper.PaperQueryRequest;
import com.ecnu.agentpapermate.entity.vo.paper.PaperSummaryVO;
import com.ecnu.agentpapermate.entity.vo.paper.PaperDetailVO;

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

    @Override
    public PaperAddVO retryPaper(Long id, Long jobId, Long userId) {
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(id == null || id <= 0 || jobId == null || jobId <= 0,
                ErrorCode.PARAMS_ERROR, "论文或任务ID不合法");
        return transactionTemplate.execute(tx -> {
            // 同一论文的手动重试串行执行，锁持有到新任务及论文状态一起提交。
            Paper paper = paperMapper.selectOne(new QueryWrapper<Paper>()
                    .eq("id", id).eq("user_id", userId).last("FOR UPDATE"));
            ThrowUtils.throwIf(paper == null, ErrorCode.NOT_FOUND_ERROR, "论文不存在或无权访问");
            ThrowUtils.throwIf(Byte.valueOf((byte) 1).equals(paper.getIsRecycled()),
                    ErrorCode.CONFLICT_ERROR, "该论文已在回收站，请恢复后重新处理");
            PaperProcessingJob latest = jobMapper.selectOne(new QueryWrapper<PaperProcessingJob>()
                    .eq("paper_id", id).orderByDesc("index_version", "id").last("LIMIT 1"));
            ThrowUtils.throwIf(latest == null, ErrorCode.CONFLICT_ERROR, "论文处理任务缺失");
            // 旧页面或网络重发只返回当前任务，不消耗新一轮处理预算。
            if (!Objects.equals(latest.getId(), jobId)) {
                return buildPaperAddVO(paper, latest);
            }
            ThrowUtils.throwIf(!"failed".equals(latest.getStatus()),
                    ErrorCode.CONFLICT_ERROR, "只有处理失败的任务可以重新处理");
            PaperProcessingJob next = buildJob(id);
            next.setIndexVersion(Math.addExact(latest.getIndexVersion(), 1));
            requireInserted(jobMapper.insert(next));
            if (paper.getActiveIndexVersion() == null) {
                requireInserted(paperMapper.update(null, new UpdateWrapper<Paper>()
                        .eq("id", id).set("status", "uploaded").setSql("update_time = CURRENT_TIMESTAMP(3)")));
                paper.setStatus("uploaded");
            }
            return buildPaperAddVO(paper, next);
        });
    }

    @Override
    public boolean setRecycled(Long id, Byte isRecycled, Long userId) {
        //1、校验目标状态，只允许回收或恢复
        ThrowUtils.throwIf(isRecycled == null || (isRecycled != 0 && isRecycled != 1),
                ErrorCode.PARAMS_ERROR, "回收状态只能为0或1");
        //2、校验登录态、论文 ID 和归属，确保只能操作本人的有效论文
        Paper paper = requireOwnedPaper(id, userId);
        //3、更新回收状态，保留论文数据及已有会话关联，重复设置同一状态仍视为成功
        paperMapper.update(null, new UpdateWrapper<Paper>()
                .eq("id", paper.getId()).eq("user_id", userId).set("is_recycled", isRecycled));
        return true;
    }

    @Override
    public Page<PaperSummaryVO> listPapers(PaperQueryRequest request, Long userId) {
        //1、校验登录态、分页边界及筛选条件，不接受客户端指定的排序字段
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getPageNum() < 1 || request.getPageSize() < 1 || request.getPageSize() > 100,
                ErrorCode.PARAMS_ERROR, "页码至少为1，每页数量为1至100");
        ThrowUtils.throwIf(request.getIsRecycled() == null || (request.getIsRecycled() != 0 && request.getIsRecycled() != 1),
                ErrorCode.PARAMS_ERROR, "回收状态只能为0或1");
        String title = keyword(request.getTitle());
        String authors = keyword(request.getAuthors());
        String topic = keyword(request.getTopic());
        String status = keyword(request.getStatus());
        ThrowUtils.throwIf(status != null && !Set.of("uploaded", "processing", "ready", "failed").contains(status),
                ErrorCode.PARAMS_ERROR, "论文状态不合法");

        //2、按回收状态查询本人未删除的论文，筛选条件以 AND 组合，固定排序避免翻页漂移
        var query = new QueryWrapper<Paper>().eq("user_id", userId).eq("is_recycled", request.getIsRecycled())
                .like(title != null, "title", title).like(authors != null, "authors", authors)
                .like(topic != null, "topic", topic).eq(status != null, "status", status)
                .orderByDesc("create_time", "id");
        Page<Paper> page = paperMapper.selectPage(new Page<>(request.getPageNum(), request.getPageSize()), query);

        //3、映射安全展示字段并保留总记录数，存储地址和执行令牌不返回客户端
        Page<PaperSummaryVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(paper -> {
            PaperSummaryVO view = new PaperSummaryVO();
            BeanUtils.copyProperties(paper, view);
            return view;
        }).toList());
        return result;
    }

    @Override
    public PaperDetailVO getPaper(Long id, Long userId) {
        //1、校验论文归属，只向所有者返回论文详情
        Paper paper = requireOwnedPaper(id, userId);
        PaperDetailVO result = new PaperDetailVO();
        BeanUtils.copyProperties(paper, result);

        //2、最新处理任务提供失败原因；重建索引失败时仍可保留旧版本的问答能力
        PaperProcessingJob latest = jobMapper.selectOne(new QueryWrapper<PaperProcessingJob>()
                .eq("paper_id", id).orderByDesc("index_version", "id").last("LIMIT 1"));
        if (latest != null) {
            result.setJobId(latest.getId());
            result.setErrorCode(latest.getErrorCode());
            result.setErrorMessage(latest.getErrorMessage());
        }

        //3、与问答创建流程保持一致，检查有效索引、执行令牌和当前向量模型兼容性
        if (paper.getActiveIndexVersion() != null && paper.getActiveIndexAttempt() != null) {
            PaperProcessingJob active = latest != null && Objects.equals(latest.getIndexVersion(), paper.getActiveIndexVersion())
                    ? latest : jobMapper.selectOne(new QueryWrapper<PaperProcessingJob>()
                    .eq("paper_id", id).eq("index_version", paper.getActiveIndexVersion()).eq("status", "completed"));
            result.setCanAsk(active != null && "completed".equals(active.getStatus())
                    && Objects.equals(active.getEmbeddingModel(), processingProperties.getEmbeddingModel())
                    && Objects.equals(active.getEmbeddingVersion(), processingProperties.getEmbeddingVersion())
                    && Objects.equals(active.getEmbeddingDimension(), processingProperties.getEmbeddingDimension()));
        }
        return result;
    }

    @Override
    public Paper requireOwnedPaper(Long id, Long userId) {
        //1、在读取任务或文件前校验登录态、论文 ID 和归属，逻辑删除由 MyBatis-Plus 过滤
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "论文ID不合法");
        Paper paper = paperMapper.selectOne(new QueryWrapper<Paper>().eq("id", id).eq("user_id", userId));
        ThrowUtils.throwIf(paper == null, ErrorCode.NOT_FOUND_ERROR, "论文不存在或无权访问");
        return paper;
    }

    private String keyword(String value) {
        //1、空白条件视为未筛选，限制搜索文本长度
        ThrowUtils.throwIf(value != null && value.length() > 255, ErrorCode.PARAMS_ERROR, "筛选条件不能超过255个字符");
        return StringUtils.hasText(value) ? value.trim() : null;
    }

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
                // 4、使用可信文件信息创建论文，时间字段取数据库默认值
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
                paper.setIsDelete((byte) 0);
                paper.setIsRecycled((byte) 0);
                requireInserted(paperMapper.insert(paper));

                // 5、创建待执行任务，任一步失败都回滚
                PaperProcessingJob job = buildJob(paper.getId());
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
     * 根据用户 ID 和文件哈希查找已有论文及其最新处理任务，不存在时返回 null。
     */
    private PaperAddVO findExisting(Long userId, String hash) {
        //1、按用户和文件哈希查找已有论文，未找到时交由上传流程创建
        Paper paper = paperMapper.selectOne(new QueryWrapper<Paper>()
                .eq("user_id", userId).eq("file_hash", hash));
        if (paper == null) {
            return null;
        }
        //2、已回收的同内容论文需先恢复，禁止通过重复上传绕过回收状态
        ThrowUtils.throwIf(Byte.valueOf((byte) 1).equals(paper.getIsRecycled()), ErrorCode.CONFLICT_ERROR,
                "该论文已在回收站，请恢复后使用");
        //3、读取最新任务，重复导入也能跟踪手动重试后的进度
        PaperProcessingJob job = jobMapper.selectOne(new QueryWrapper<PaperProcessingJob>()
                .eq("paper_id", paper.getId()).orderByDesc("index_version", "id").last("LIMIT 1"));
        ThrowUtils.throwIf(job == null, ErrorCode.OPERATION_ERROR, "论文已存在但处理任务缺失，请联系管理员");

        //4、返回已有论文和任务，并标记重复上传，供前端展示提示
        PaperAddVO result = buildPaperAddVO(paper, job);
        result.setAlreadyExists(true);
        return result;
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
    private PaperProcessingJob buildJob(Long paperId) {
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
