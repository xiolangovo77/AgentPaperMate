package com.ecnu.agentpapermate.service.run.rag;

import com.ecnu.agentpapermate.rag.model.Answer;
import com.ecnu.agentpapermate.rag.model.Evidence;
import com.ecnu.agentpapermate.rag.model.PaperScope;
import com.ecnu.agentpapermate.rag.model.RetrievalResult;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ecnu.agentpapermate.config.paper.PaperProcessingProperties;
import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.entity.dto.run.RunCreateRequest;
import com.ecnu.agentpapermate.entity.model.*;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.exception.ThrowUtils;
import com.ecnu.agentpapermate.mapper.*;
import com.ecnu.agentpapermate.validator.AnswerValidator;
import com.ecnu.agentpapermate.support.json.RagJson;
import com.ecnu.agentpapermate.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/** Run 状态与事件的事务边界；先锁聊天室，再锁 Run，事务内不访问外部模型或 ES。 */
@Service
@RequiredArgsConstructor
public class RunStore {
    /**
     * 问答任务数据访问接口，负责任务查询、加锁和状态持久化。
     */
    private final AgentRunMapper runs;
    /**
     * 聊天室数据访问接口，负责设置和释放活动任务占用。
     */
    private final ChatSessionMapper sessions;
    /**
     * 聊天室业务服务，校验会话归属并提供会话行锁。
     */
    private final ChatSessionService sessionService;
    /**
     * 会话论文关联数据访问接口，用于读取本次任务的资料范围。
     */
    private final SessionPaperMapper relations;
    /**
     * 论文数据访问接口，用于校验资料归属、可访问性及有效索引版本。
     */
    private final PaperMapper papers;
    /**
     * 论文处理任务数据访问接口，用于读取已完成索引的模型和物理索引配置。
     */
    private final PaperProcessingJobMapper jobs;
    /**
     * 聊天消息数据访问接口，在任务事务中保存用户问题和最终助手回答。
     */
    private final ChatMessageMapper messages;
    /**
     * 回答引用数据访问接口，保存助手消息实际引用的原文及来源快照。
     */
    private final MessageCitationMapper citations;
    /**
     * 运行事件数据访问接口，与任务状态在同一事务中保存进度和终态事件。
     */
    private final RunEventMapper events;
    /**
     * 任务 JSON 转换工具，用于读写资料快照、证据快照及事件载荷。
     */
    private final RagJson json;
    /**
     * 问答运行配置，提供模型选项、执行预算和资料数量上限。
     */
    private final RagProperties properties;
    /**
     * 当前论文索引配置，用于核对资料快照与向量模型的兼容性。
     */
    private final PaperProcessingProperties processing;
    /**
     * 上海时区时钟，用于记录任务开始、结束时间及判断是否超时。
     */
    private final Clock ragClock;

    @Transactional(rollbackFor = Exception.class)
    public Long create(RunCreateRequest request, Long userId) {
        //1、校验输入并锁定用户聊天室，重发优先返回原任务
        validateRequest(request);
        ChatSession session = sessionService.lockSession(request.getSessionId(), userId);
        AgentRun existing = runs.selectOne(new QueryWrapper<AgentRun>().eq("session_id", session.getId())
                .eq("request_key", request.getRequestKey()));
        if (existing != null) {
            ThrowUtils.throwIf(!existing.getUserQuery().equals(request.getQuestion())
                            || !Objects.equals(existing.getRetryOfRunId(), request.getRetryOfRunId()),
                    ErrorCode.CONFLICT_ERROR, "同一幂等键不能用于不同请求");
            return existing.getId();
        }
        ThrowUtils.throwIf(!properties.isEnabled(), ErrorCode.OPERATION_ERROR, "问答功能尚未开启");
        ThrowUtils.throwIf(session.getActiveRunId() != null, ErrorCode.CONFLICT_ERROR, "聊天室已有活动问答任务");
        if (request.getRetryOfRunId() != null) {
            AgentRun previous = runs.selectById(request.getRetryOfRunId());
            ThrowUtils.throwIf(previous == null || !session.getId().equals(previous.getSessionId())
                    || !userId.equals(previous.getUserId()) || !terminal(previous.getStatus()),
                    ErrorCode.PARAMS_ERROR, "只能重新执行本聊天室已结束的任务");
        }

        //2、在会话锁内生成论文与索引快照，不信任前端传入的资料范围
        List<PaperScope> scope = snapshot(session.getId(), userId);
        AgentRun run = new AgentRun();
        run.setSessionId(session.getId()); run.setUserId(userId); run.setRequestKey(request.getRequestKey());
        run.setUserQuery(request.getQuestion()); run.setRetryOfRunId(request.getRetryOfRunId());
        run.setExecutorType("rag"); run.setStatus("pending"); run.setScopeVersion(session.getScopeVersion());
        run.setExecutionPhase("queued");
        run.setPapersSnapshot(json.write(scope)); run.setModelName(properties.getModel());
        run.setModelConfig(json.write(Map.of("maxTokens", properties.getMaxOutputTokens(), "temperature", 0.2)));
        run.setEventSeq(0L); run.setInputTokens(0L); run.setOutputTokens(0L);
        run.setStepCount(0); run.setToolCallCount(0); run.setIsDelete((byte) 0);
        changed(runs.insert(run));

        //3、同一事务保存用户消息并占用聊天室，失败时整体回滚
        ChatMessage message = message(run, "user", request.getQuestion());
        changed(messages.insert(message));
        changed(sessions.assignActiveRun(session.getId(), run.getId()));
        return run.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentRun start(Long id) {
        //1、串行领取同一任务，只有 pending 可以进入 running
        AgentRun run = lock(id);
        if (run == null || !"pending".equals(run.getStatus())) return null;
        if (queueExpired(run)) {
            finish(run, "timeout", "QUEUE_TIMEOUT", "任务排队超时", Map.of());
            return null;
        }
        //2、领取与开始事件一起提交，执行器只能在提交后调用外部服务
        run.setStatus("running"); run.setStartTime(now());
        run.setExecutionPhase("ready");
        run.setDeadline(now().plusSeconds(properties.getRunTimeoutSeconds()));
        changed(runs.updateById(run));
        append(run, "run_started", Map.of("status", "running"));
        return run;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean retrievalStarted(Long id) {
        //1、只允许存活任务开始检索，重复执行不会重复写入开始事件
        AgentRun run = live(id);
        if (run == null) return false;
        // 后续轮次可从证据就绪或模型已调用阶段开始；retrieving 仍拒绝重复开始。
        requirePhase(run, "ready", "evidence_ready", "generating");
        verifyAccess(run);
        run.setExecutionPhase("retrieving");
        // append 同时持久化阶段和事件序号，与事件记录处于同一事务。
        append(run, "retrieval_started", Map.of());
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean retrieved(Long id, RetrievalResult retrieval) {
        //1、原子累计筛选前候选、保存本轮证据和完成事件，取消或超时后不再推进阶段
        AgentRun run = live(id);
        if (run == null) return false;
        requirePhase(run, "retrieving");
        verifyAccess(run);
        // 在行锁内再合并一次，避免调用方只传本轮候选或传入较旧的累计快照时覆盖历史。
        var retrievedChunkIds = new LinkedHashSet<String>();
        if (run.getRetrievalSnapshot() != null) {
            retrievedChunkIds.addAll(json.retrievedChunkIds(run.getRetrievalSnapshot()));
        }
        retrievedChunkIds.addAll(retrieval.retrievedChunkIds());
        run.setRetrievalSnapshot(json.write(List.copyOf(retrievedChunkIds)));
        run.setEvidenceSnapshot(json.write(retrieval.evidence()));
        run.setExecutionPhase("evidence_ready");
        changed(runs.updateById(run));
        append(run, "retrieval_completed", Map.of("evidenceCount", retrieval.evidence().size(),
                "candidateCount", retrievedChunkIds.size()));
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean modelStarted(Long id) {
        //1、调用模型前再次核对任务状态和权限，记录实际发起的模型轮数
        AgentRun run = live(id);
        if (run == null) return false;
        // generating 允许后续受控修复再次调用模型，每次仍累计轮数。
        requirePhase(run, "evidence_ready", "generating");
        verifyAccess(run);
        requireEvidence(run);
        run.setExecutionPhase("generating");
        run.setStepCount(run.getStepCount() + 1);
        changed(runs.updateById(run));
        return true;
    }

    /** 已发生的模型消耗独立提交，后续答案校验失败不回滚；同一次响应只能调用一次。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void addModelUsage(Long id, long inputTokens, long outputTokens) {
        if (id == null || id <= 0 || inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("任务 ID 或模型用量无效");
        }
        // 沿用会话、Run 锁顺序，避免其他状态事务的 updateById 覆盖累计值。
        // 不用 live：已取得的用量可在取消或超时后记账，但不改变任务状态或发布答案。
        AgentRun run = lock(id);
        if (run == null) throw new IllegalStateException("用量所属任务不存在");
        Math.addExact(run.getInputTokens(), inputTokens);
        Math.addExact(run.getOutputTokens(), outputTokens);
        if (inputTokens == 0 && outputTokens == 0) return;
        changed(runs.addModelUsage(id, inputTokens, outputTokens));
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean complete(Long id, Answer answer) {
        //1、加锁后重新确认任务仍在运行，过期或取消结果不得迟到写入
        AgentRun run = live(id);
        if (run == null) return false;
        // 空检索结果可在 evidence_ready 阶段直接给出无法确认的回答。
        requirePhase(run, "evidence_ready", "generating");
        verifyAccess(run);
        List<Evidence> evidence = requireEvidence(run);
        AnswerValidator.validate(answer, evidence);

        //2、先创建助手消息，再把实际引用的证据原文绑定到助手消息
        ChatMessage message = message(run, "assistant", answer.content());
        changed(messages.insert(message));
        Map<String, Evidence> byId = new HashMap<>();
        evidence.forEach(item -> byId.put(item.evidenceId(), item));
        List<MessageCitation> saved = new ArrayList<>();
        for (String evidenceId : new LinkedHashSet<>(answer.evidenceIds())) {
            Evidence source = byId.get(evidenceId);
            MessageCitation citation = new MessageCitation();
            citation.setMessageId(message.getId()); citation.setEvidenceId(source.evidenceId());
            citation.setPaperId(source.paperId()); citation.setPaperTitle(source.paperTitle());
            citation.setIndexVersion(source.indexVersion()); citation.setEsIndexName(source.esIndexName());
            citation.setChunkId(source.chunkId()); citation.setPageStart(source.pageStart()); citation.setPageEnd(source.pageEnd());
            citation.setQuotedText(source.quotedText()); citation.setFileUrl(source.fileUrl()); citation.setIsDelete((byte) 0);
            changed(citations.insert(citation)); saved.add(citation);
        }

        //3、消息、引用、终态及最终事件原子提交，SSE 仅读取提交后的事件
        finish(run, "completed", null, null, Map.of("messageId", message.getId(), "content", answer.content(), "citations", saved));
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public String cancel(Long id, Long userId) {
        //1、取消接口必须验证登录用户对任务和会话的所有权
        AgentRun owned = owned(id, userId);
        AgentRun run = lock(owned.getId());
        if (!terminal(run.getStatus())) {
            //2、截止时间优先于迟到的取消请求，终态只能成功写入一次
            if (expired(run) || queueExpired(run)) finish(run, "timeout", "RUN_TIMEOUT", "问答任务超时", Map.of());
            else finish(run, "cancelled", "USER_CANCELLED", "用户已停止生成", Map.of());
        }
        return run.getStatus();
    }

    @Transactional(rollbackFor = Exception.class)
    public void fail(Long id, String code, String message, boolean timeout) {
        //1、失败回写也遵守会话、任务锁顺序，已有终态保持不变
        AgentRun run = lock(id);
        if (run == null || terminal(run.getStatus())) return;
        boolean timedOut = timeout || expired(run) || queueExpired(run);
        finish(run, timedOut ? "timeout" : "failed", timedOut ? "RUN_TIMEOUT" : code,
                timedOut ? "问答任务超时" : message, Map.of());
    }

    @Transactional(rollbackFor = Exception.class)
    public void expire(Long id) {
        //1、扫描只提供候选 ID，在锁内重新核对截止时间，避免结束刚领取的任务
        AgentRun run = lock(id);
        if (run != null && !terminal(run.getStatus()) && (expired(run) || queueExpired(run))) {
            finish(run, "timeout", "RUN_TIMEOUT", "问答任务超时", Map.of());
        }
    }

    public AgentRun owned(Long id, Long userId) {
        //1、先校验身份，再查询所属任务和有效聊天室
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "任务ID不合法");
        AgentRun run = runs.selectOne(new QueryWrapper<AgentRun>().eq("id", id).eq("user_id", userId));
        ThrowUtils.throwIf(run == null, ErrorCode.NOT_FOUND_ERROR, "任务不存在或无权访问");
        sessionService.getSession(run.getSessionId(), userId);
        return run;
    }

    public static boolean terminal(String status) {
        return Set.of("completed", "failed", "cancelled", "timeout").contains(status);
    }

    private AgentRun lock(Long id) {
        //1、先读取不可变归属，再统一按会话、Run 顺序加锁
        AgentRun initial = runs.selectById(id);
        if (initial == null) return null;
        sessionService.lockSession(initial.getSessionId(), initial.getUserId());
        return runs.selectForUpdate(id);
    }

    private AgentRun live(Long id) {
        AgentRun run = lock(id);
        if (run == null || !"running".equals(run.getStatus())) return null;
        if (expired(run)) {
            finish(run, "timeout", "RUN_TIMEOUT", "问答任务超时", Map.of());
            return null;
        }
        return run;
    }

    private void requirePhase(AgentRun run, String... allowed) {
        if (!Arrays.asList(allowed).contains(run.getExecutionPhase())) {
            throw new IllegalStateException("任务执行阶段不允许此操作：" + run.getExecutionPhase());
        }
    }

    private List<Evidence> requireEvidence(AgentRun run) {
        // null 表示未完成检索，[] 表示检索完成但没有证据，两者不能混用。
        if (run.getRetrievalSnapshot() == null || run.getEvidenceSnapshot() == null) {
            throw new IllegalStateException("任务尚未保存检索及证据快照");
        }
        json.retrievedChunkIds(run.getRetrievalSnapshot());
        List<Evidence> evidence = json.evidence(run.getEvidenceSnapshot());
        if (evidence == null || evidence.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("任务证据快照必须是有效数组");
        }
        return evidence;
    }

    private void finish(AgentRun run, String status, String code, String error, Map<String, Object> result) {
        //1、更新终态和结束时间，保留已累计用量；必须在持有任务锁的事务内调用
        run.setStatus(status); run.setEndTime(now()); run.setErrorCode(code); run.setErrorMessage(error);
        changed(runs.updateById(run));
        Map<String, Object> payload = new LinkedHashMap<>(result);
        payload.put("status", status);
        if (code != null) { payload.put("errorCode", code); payload.put("errorMessage", error); }
        append(run, "run_" + status, payload);
        //2、仅释放仍指向当前 Run 的占用，避免清除其他任务
        changed(sessions.releaseActiveRun(run.getSessionId(), run.getId()));
    }

    private void append(AgentRun run, String type, Object payload) {
        //1、序号只用于事件排序和补发；事件写入失败会回滚序号及同事务业务阶段
        run.setEventSeq(run.getEventSeq() + 1);
        changed(runs.updateById(run));
        RunEvent event = new RunEvent();
        event.setRunId(run.getId()); event.setEventSeq(run.getEventSeq()); event.setEventType(type);
        event.setPayload(json.write(payload)); event.setCreateTime(now()); event.setIsDelete((byte) 0);
        changed(events.insert(event));
    }

    private List<PaperScope> snapshot(Long sessionId, Long userId) {
        //1、限制资料数量，所有选中论文必须有可用索引，避免悄悄漏掉资料
        List<SessionPaper> selected = relations.selectActiveForRun(sessionId, properties.getMaxPapers() + 1);
        ThrowUtils.throwIf(selected.isEmpty() || selected.size() > properties.getMaxPapers(),
                ErrorCode.PARAMS_ERROR, "请选择1至" + properties.getMaxPapers() + "篇已完成索引的论文");
        List<PaperScope> result = new ArrayList<>();
        for (SessionPaper relation : selected) {
            Paper paper = papers.selectOne(new QueryWrapper<Paper>().eq("id", relation.getPaperId()).eq("user_id", userId));
            ThrowUtils.throwIf(paper == null || paper.getActiveIndexVersion() == null || paper.getActiveIndexAttempt() == null,
                    ErrorCode.CONFLICT_ERROR, "资料中存在未就绪或不可访问的论文");
            PaperProcessingJob job = jobs.selectOne(new QueryWrapper<PaperProcessingJob>().eq("paper_id", paper.getId())
                    .eq("index_version", paper.getActiveIndexVersion()).eq("status", "completed"));
            ThrowUtils.throwIf(job == null || !Objects.equals(job.getEmbeddingModel(), processing.getEmbeddingModel())
                    || !Objects.equals(job.getEmbeddingVersion(), processing.getEmbeddingVersion())
                    || !Objects.equals(job.getEmbeddingDimension(), processing.getEmbeddingDimension()),
                    ErrorCode.CONFLICT_ERROR, "论文索引与当前向量模型不兼容，请重新处理论文");
            result.add(new PaperScope(paper.getId(), paper.getTitle(), paper.getActiveIndexVersion(), paper.getActiveIndexAttempt(),
                    job.getEsIndexName(), job.getEmbeddingModel(), job.getEmbeddingVersion(), job.getEmbeddingDimension(), paper.getFileUrl()));
        }
        return result;
    }

    private void verifyAccess(AgentRun run) {
        //1、资料移出仅影响下次任务，实际删除或撤销归属则禁止继续访问
        for (PaperScope scope : json.scopes(run.getPapersSnapshot())) {
            if (papers.selectCount(new QueryWrapper<Paper>().eq("id", scope.paperId()).eq("user_id", run.getUserId())) != 1L) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "任务资料已不可访问");
            }
        }
    }

    private ChatMessage message(AgentRun run, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(run.getSessionId()); message.setRunId(run.getId()); message.setRole(role);
        message.setMessageType("text"); message.setContent(content); message.setIsDelete((byte) 0);
        return message;
    }

    private void validateRequest(RunCreateRequest request) {
        ThrowUtils.throwIf(request == null || request.getRequestKey() == null
                || !request.getRequestKey().matches("[A-Za-z0-9_-]{16,64}") || request.getQuestion() == null
                || request.getQuestion().isBlank() || request.getQuestion().getBytes(StandardCharsets.UTF_8).length > 16000,
                ErrorCode.PARAMS_ERROR, "请提供16至64位幂等键和不超过16000字节的非空问题");
    }

    private boolean expired(AgentRun run) {
        return "running".equals(run.getStatus()) && run.getDeadline() != null && !now().isBefore(run.getDeadline());
    }

    private boolean queueExpired(AgentRun run) {
        return "pending".equals(run.getStatus()) && run.getCreateTime() != null
                && !now().isBefore(run.getCreateTime().plusSeconds(properties.getQueueTimeoutSeconds()));
    }

    private LocalDateTime now() { return LocalDateTime.now(ragClock); }

    private void changed(int rows) {
        if (rows != 1) throw new IllegalStateException("任务状态或消息写入失败");
    }
}
