package com.ecnu.agentpapermate.service.run.rag;

import com.ecnu.agentpapermate.entity.vo.run.EventView;
import com.ecnu.agentpapermate.entity.vo.run.RunView;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecnu.agentpapermate.entity.model.*;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.exception.ThrowUtils;
import com.ecnu.agentpapermate.mapper.*;
import com.ecnu.agentpapermate.support.json.RagJson;
import com.ecnu.agentpapermate.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 任务、消息与事件查询，每个对外入口都校验会话所有权。 */
@Service
@RequiredArgsConstructor
public class RunQueries {
    /**
     * 任务状态服务，校验当前用户对任务的访问权限。
     */
    private final RunStore store;
    /**
     * 运行事件数据访问接口，按任务内序号查询可补发事件。
     */
    private final RunEventMapper events;
    /**
     * 聊天消息数据访问接口，查询最终回答、历史问题及会话消息分页。
     */
    private final ChatMessageMapper messages;
    /**
     * 回答引用数据访问接口，查询助手消息关联的引用快照。
     */
    private final MessageCitationMapper citations;
    /**
     * 聊天室业务服务，校验消息及引用所属会话的访问权限。
     */
    private final ChatSessionService sessions;
    /**
     * 任务 JSON 转换工具，将持久化事件载荷转换为响应数据。
     */
    private final RagJson json;

    public RunView view(Long runId, Long userId) {
        //1、验证任务归属，只有已完成任务对外提供最终助手消息和引用
        AgentRun run = store.owned(runId, userId);
        ChatMessage answer = "completed".equals(run.getStatus()) ? messages.selectOne(new QueryWrapper<ChatMessage>()
                .eq("run_id", runId).eq("role", "assistant")) : null;
        List<MessageCitation> sources = answer == null ? List.of() : citations.selectList(
                new QueryWrapper<MessageCitation>().eq("message_id", answer.getId()).orderByAsc("id"));
        return new RunView(runId, run.getSessionId(), run.getStatus(), run.getEventSeq(), run.getStartTime(),
                run.getEndTime(), run.getDeadline(), run.getErrorCode(), run.getErrorMessage(), answer, sources);
    }

    public List<EventView> events(Long runId, Long after, Long userId) {
        //1、客户端游标只能读取所属任务，不允许负数或超出当前最大序号
        AgentRun run = store.owned(runId, userId);
        validateCursor(after, run);
        return events.selectAfterSequence(runId, after, 100).stream()
                .map(event -> new EventView(event.getRunId(), event.getEventSeq(), event.getEventType(),
                        event.getCreateTime(), json.tree(event.getPayload()))).toList();
    }

    public void validateCursor(Long after, AgentRun run) {
        ThrowUtils.throwIf(after == null || after < 0 || after > run.getEventSeq(), ErrorCode.PARAMS_ERROR, "事件游标不合法");
    }

    public Page<ChatMessage> messages(Long sessionId, long pageNum, long pageSize, Long userId) {
        //1、分页返回聊天正文和资料变更消息，统一限制单页数量
        sessions.getSession(sessionId, userId);
        ThrowUtils.throwIf(pageNum < 1 || pageSize < 1 || pageSize > 100, ErrorCode.PARAMS_ERROR, "分页参数不合法");
        return messages.selectPage(new Page<>(pageNum, pageSize), new QueryWrapper<ChatMessage>()
                .eq("session_id", sessionId).orderByDesc("id"));
    }

    public List<MessageCitation> citations(Long messageId, Long userId) {
        //1、引用查询沿助手消息验证所属聊天室，不能直接按引用 ID 读取其他用户数据
        ThrowUtils.throwIf(messageId == null || messageId <= 0, ErrorCode.PARAMS_ERROR);
        ChatMessage message = messages.selectById(messageId);
        ThrowUtils.throwIf(message == null, ErrorCode.NOT_FOUND_ERROR, "消息不存在");
        sessions.getSession(message.getSessionId(), userId);
        return citations.selectList(new QueryWrapper<MessageCitation>().eq("message_id", messageId).orderByAsc("id"));
    }

    public List<String> previousQuestions(AgentRun run) {
        //1、历史仅用于追问理解，旧答案及旧引用不进入本次证据集合
        List<ChatMessage> recent = messages.selectPreviousQuestions(run.getSessionId(), run.getId(), 6);
        List<String> result = new ArrayList<>();
        int budget = 4000;
        for (ChatMessage message : recent) {
            String content = message.getContent();
            if (content.length() > budget) continue;
            result.add(content); budget -= content.length();
        }
        Collections.reverse(result);
        return result;
    }
}
