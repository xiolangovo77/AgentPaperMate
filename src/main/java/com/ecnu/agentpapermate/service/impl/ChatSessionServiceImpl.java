package com.ecnu.agentpapermate.service.impl;

import com.ecnu.agentpapermate.entity.model.ChatSession;
import com.ecnu.agentpapermate.mapper.ChatSessionMapper;
import com.ecnu.agentpapermate.service.ChatSessionService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecnu.agentpapermate.entity.dto.chatSession.ChatSessionAddRequest;
import com.ecnu.agentpapermate.entity.dto.chatSession.ChatSessionQueryRequest;
import com.ecnu.agentpapermate.entity.dto.chatSession.ChatSessionUpdateRequest;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ThrowUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * <p>
 * 研究聊天室 服务实现类
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession> implements ChatSessionService {

    private final ChatSessionMapper chatSessionMapper;

    @Override
    public Long addSession(ChatSessionAddRequest request, Long userId) {
        //1、校验登录用户、名称和草稿长度
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        validateFields(request.getName(), request.getInputDraft());

        //2、只接受可编辑字段，ID由框架生成，时间使用数据库默认值
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setName(request.getName() == null ? "新研究会话" : request.getName().strip());
        session.setInputDraft(request.getInputDraft());
        session.setScopeVersion(1);
        session.setIsDelete((byte) 0);

        //3、保存聊天室并返回聊天室ID
        try {
            ThrowUtils.throwIf(!save(session), ErrorCode.OPERATION_ERROR, "新增聊天室失败");
        } catch (DuplicateKeyException e) {
            // 聊天室仅有主键唯一约束，同名聊天室可以正常创建。
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "聊天室ID冲突，请重试");
        }
        return session.getId();
    }

    @Override
    public ChatSession getSession(Long id, Long userId) {
        //1、校验登录态和聊天室ID
        validateIdentity(id, userId);

        //2、仅查询当前用户未删除的聊天室
        ChatSession session = chatSessionMapper.selectOne(new QueryWrapper<ChatSession>()
                .eq("id", id).eq("user_id", userId));
        ThrowUtils.throwIf(session == null, ErrorCode.NOT_FOUND_ERROR, "聊天室不存在或无权访问");
        return session;
    }

    @Override
    public Page<ChatSession> listSessions(ChatSessionQueryRequest request, Long userId) {
        //1、校验分页范围，限制单次查询数量
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getPageNum() < 1 || request.getPageSize() < 1
                || request.getPageSize() > 100, ErrorCode.PARAMS_ERROR, "页码至少为1，每页数量为1至100");
        String name = request.getName();
        ThrowUtils.throwIf(name != null && name.codePointCount(0, name.length()) > 200,
                ErrorCode.PARAMS_ERROR, "名称关键字不能超过200个字符");

        //2、限定当前用户，固定排序避免客户端拼接排序SQL
        QueryWrapper<ChatSession> query = new QueryWrapper<ChatSession>().eq("user_id", userId);
        if (name != null && !name.isBlank()) {
            query.like("name", name.strip());
        }
        query.orderByDesc("last_message_time", "id");
        return chatSessionMapper.selectPage(new Page<>(request.getPageNum(), request.getPageSize()), query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateSession(ChatSessionUpdateRequest request, Long userId) {
        //1、校验可编辑字段，null表示不修改，空草稿表示清空
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        validateFields(request.getName(), request.getInputDraft());
        ThrowUtils.throwIf(request.getName() == null && request.getInputDraft() == null,
                ErrorCode.PARAMS_ERROR, "至少提供一个待更新字段");

        //2、锁定当前用户的聊天室，重复提交相同内容直接成功
        ChatSession existing = lockSession(request.getId(), userId);
        boolean nameChanged = request.getName() != null
                && !request.getName().strip().equals(existing.getName());
        boolean draftChanged = request.getInputDraft() != null
                && !request.getInputDraft().equals(existing.getInputDraft());
        if (!nameChanged && !draftChanged) {
            return true;
        }

        //3、仅更新名称和草稿，不修改最近活动时间、论文范围版本或活动任务
        UpdateWrapper<ChatSession> update = new UpdateWrapper<ChatSession>()
                .eq("id", request.getId()).eq("user_id", userId);
        if (nameChanged) {
            update.set("name", request.getName().strip());
        }
        if (draftChanged) {
            update.set("input_draft", request.getInputDraft());
        }
        update.setSql("update_time = CURRENT_TIMESTAMP(3)");
        ThrowUtils.throwIf(chatSessionMapper.update(null, update) != 1,
                ErrorCode.OPERATION_ERROR, "更新聊天室失败");
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteSession(Long id, Long userId) {
        //1、与创建 Run、资料变更共用会话锁，在事务内校验归属及活动任务
        ChatSession session = lockSession(id, userId);
        ThrowUtils.throwIf(session.getActiveRunId() != null, ErrorCode.CONFLICT_ERROR,
                "聊天室已有活动问答任务，请等待任务结束后再删除");

        //2、按会话批量逻辑删除子记录；没有关联数据时影响零行是正常情况
        chatSessionMapper.logicalDeleteCitations(id);
        chatSessionMapper.logicalDeleteRunEvents(id);
        chatSessionMapper.logicalDeleteMessages(id);
        chatSessionMapper.logicalDeleteRuns(id);
        chatSessionMapper.logicalDeleteSessionPapers(id);

        //3、最后删除会话，条件更新兜底；任何 SQL 异常或会话更新失败都整体回滚
        ThrowUtils.throwIf(chatSessionMapper.logicalDeleteIdleSession(id, userId) != 1,
                ErrorCode.OPERATION_ERROR, "删除聊天室失败");
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ChatSession lockSession(Long id, Long userId) {
        //1、校验登录态和聊天室ID
        validateIdentity(id, userId);

        //2、在调用方事务内锁定记录，串行处理同一会话的更新
        ChatSession session = chatSessionMapper.selectOwnedForUpdate(id, userId);
        ThrowUtils.throwIf(session == null, ErrorCode.NOT_FOUND_ERROR, "聊天室不存在或无权访问");
        return session;
    }

    private void validateIdentity(Long id, Long userId) {
        //1、校验用户身份与正数ID
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "聊天室ID不合法");
    }

    private void validateFields(String name, String inputDraft) {
        //1、名称不能为空白且不能超过数据库字符上限
        if (name != null) {
            String normalized = name.strip();
            ThrowUtils.throwIf(normalized.isBlank() || normalized.codePointCount(0, normalized.length()) > 200,
                    ErrorCode.PARAMS_ERROR, "聊天室名称须为1至200个字符");
        }

        //2、TEXT字段以UTF-8字节长度校验，避免中文或表情导致数据库溢出
        ThrowUtils.throwIf(inputDraft != null && inputDraft.getBytes(StandardCharsets.UTF_8).length > 65535,
                ErrorCode.PARAMS_ERROR, "输入草稿不能超过65535字节");
    }
}
