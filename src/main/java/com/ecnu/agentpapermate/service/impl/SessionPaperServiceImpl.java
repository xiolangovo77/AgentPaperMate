package com.ecnu.agentpapermate.service.impl;

import com.ecnu.agentpapermate.entity.model.SessionPaper;
import com.ecnu.agentpapermate.mapper.SessionPaperMapper;
import com.ecnu.agentpapermate.service.SessionPaperService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecnu.agentpapermate.entity.dto.sessionPaper.SessionPaperAddRequest;
import com.ecnu.agentpapermate.entity.dto.sessionPaper.SessionPaperQueryRequest;
import com.ecnu.agentpapermate.entity.dto.sessionPaper.SessionPaperUpdateRequest;
import com.ecnu.agentpapermate.entity.model.Paper;
import com.ecnu.agentpapermate.entity.model.ChatSession;
import com.ecnu.agentpapermate.entity.model.ChatMessage;
import com.ecnu.agentpapermate.mapper.ChatMessageMapper;
import com.ecnu.agentpapermate.support.json.RagJson;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.TreeSet;
import java.util.Objects;
import com.ecnu.agentpapermate.entity.dto.sessionPaper.SessionPaperScopeRequest;
import com.ecnu.agentpapermate.entity.vo.sessionPaper.SessionPaperScopeVO;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ThrowUtils;
import com.ecnu.agentpapermate.mapper.ChatSessionMapper;
import com.ecnu.agentpapermate.mapper.PaperMapper;
import com.ecnu.agentpapermate.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 * 聊天室当前论文关联 服务实现类
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
@Service
@RequiredArgsConstructor
public class SessionPaperServiceImpl extends ServiceImpl<SessionPaperMapper, SessionPaper> implements SessionPaperService {

    /**
     * 会话论文关联数据访问接口，负责新增、恢复、移出及查询资料关联。
     */
    private final SessionPaperMapper sessionPaperMapper;
    /**
     * 聊天室数据访问接口，资料变化时递增范围版本并更新最近活动时间。
     */
    private final ChatSessionMapper chatSessionMapper;
    /**
     * 论文数据访问接口，用于校验资料归属和读取论文标题。
     */
    private final PaperMapper paperMapper;
    /**
     * 聊天室业务服务，校验会话归属并通过会话锁串行处理资料变更。
     */
    private final ChatSessionService chatSessionService;
    /**
     * 聊天消息数据访问接口，与资料变更在同一事务中保存系统消息。
     */
    private final ChatMessageMapper chatMessageMapper;
    /**
     * JSON 转换工具，用于保存资料变更前后论文集合、范围版本等补充信息。
     */
    private final RagJson ragJson;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SessionPaperScopeVO updateScope(SessionPaperScopeRequest request, Long userId) {
        //1、校验完整集合和版本，null 不代表清空；限制单次输入，避免长时间持有会话锁
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null || request.getPaperIds() == null || request.getScopeVersion() == null,
                ErrorCode.PARAMS_ERROR, "sessionId、paperIds和scopeVersion不能为空");
        ThrowUtils.throwIf(request.getScopeVersion() < 0 || request.getPaperIds().size() > 100,
                ErrorCode.PARAMS_ERROR, "范围版本不能为负数，单次最多提交100个论文ID");
        request.getPaperIds().forEach(this::validateId);
        List<Long> after = new ArrayList<>(new TreeSet<>(request.getPaperIds()));

        //2、锁定本人会话并检查版本，读取原范围以区分保留关联和新增关联，再校验目标论文
        ChatSession session = chatSessionService.lockSession(request.getSessionId(), userId);
        ThrowUtils.throwIf(!Objects.equals(request.getScopeVersion(), session.getScopeVersion()),
                ErrorCode.CONFLICT_ERROR, "论文范围已变化，请刷新范围和版本后重试");
        List<SessionPaper> current = sessionPaperMapper.selectList(new QueryWrapper<SessionPaper>()
                .eq("session_id", session.getId()).orderByAsc("paper_id"));
        List<Long> before = current.stream().map(SessionPaper::getPaperId).sorted().toList();
        after.forEach(id -> requireOwnedPaper(id, userId, before.contains(id)));
        int version = session.getScopeVersion();

        //3、只在集合实际变化时移出、恢复或新增关联，失败时整体回滚
        if (!before.equals(after)) {
            ThrowUtils.throwIf(version == Integer.MAX_VALUE, ErrorCode.CONFLICT_ERROR, "论文范围版本已达上限");
            for (SessionPaper relation : current) {
                if (!after.contains(relation.getPaperId())) {
                    requireChanged(sessionPaperMapper.updateMembership(relation.getId(), (byte) 1));
                }
            }
            for (Long paperId : after) {
                if (before.contains(paperId)) continue;
                SessionPaper existing = sessionPaperMapper.selectPairForUpdate(session.getId(), paperId);
                if (existing != null) {
                    requireChanged(sessionPaperMapper.updateMembership(existing.getId(), (byte) 0));
                } else {
                    SessionPaper relation = new SessionPaper();
                    relation.setSessionId(session.getId());
                    relation.setPaperId(paperId);
                    relation.setIsDelete((byte) 0);
                    requireChanged(sessionPaperMapper.insert(relation));
                }
            }

            //4、一次替换只递增一次版本并写一条系统消息，既有问答任务快照保持独立
            requireChanged(chatSessionMapper.incrementScopeVersion(session.getId(), userId));
            version++;
            ChatMessage message = new ChatMessage();
            message.setSessionId(session.getId());
            message.setRole("system");
            message.setMessageType("scope_change");
            message.setContent("已更新资料范围，共" + after.size() + "篇论文");
            message.setExtraData(ragJson.write(Map.of("beforePaperIds", before, "afterPaperIds", after,
                    "scopeVersion", version, "action", "replaced")));
            message.setIsDelete((byte) 0);
            requireChanged(chatMessageMapper.insert(message));
        }

        //5、返回本事务内的完整最新集合与版本，无变化请求不产生额外写入
        SessionPaperScopeVO result = new SessionPaperScopeVO();
        result.setSessionId(session.getId());
        result.setPaperIds(after);
        result.setScopeVersion(version);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addSessionPaper(SessionPaperAddRequest request, Long userId) {
        //1、校验请求并锁定所属聊天室，避免并发重复添加或丢失版本更新
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        validateId(request.getPaperId());
        ChatSession session = chatSessionService.lockSession(request.getSessionId(), userId);
        requireOwnedPaper(request.getPaperId(), userId);

        //2、读取含已移出的关联，重复添加当前论文直接返回原ID
        SessionPaper existing = sessionPaperMapper.selectPairForUpdate(request.getSessionId(), request.getPaperId());
        if (existing != null && existing.getIsDelete() == 0) {
            return existing.getId();
        }

        //3、恢复原关联或创建新关联，保留首次关联时间和唯一键
        Long id;
        if (existing != null) {
            requireChanged(sessionPaperMapper.updateMembership(existing.getId(), (byte) 0));
            id = existing.getId();
        } else {
            SessionPaper relation = new SessionPaper();
            relation.setSessionId(request.getSessionId());
            relation.setPaperId(request.getPaperId());
            relation.setIsDelete((byte) 0);
            try {
                ThrowUtils.throwIf(!save(relation), ErrorCode.OPERATION_ERROR, "新增论文关联失败");
            } catch (DuplicateKeyException e) {
                // 唯一索引兜底约束重复关联，抛出业务异常使当前事务回滚。
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "论文关联记录冲突，请刷新后重试");
            }
            id = relation.getId();
        }

        //4、同一事务内递增论文范围版本并更新最近活动时间，任一步失败都回滚
        requireChanged(chatSessionMapper.incrementScopeVersion(request.getSessionId(), userId));
        recordScopeChange(session, request.getPaperId(), true);
        return id;
    }

    @Override
    public SessionPaper getSessionPaper(Long id, Long userId) {
        //1、校验登录用户与关联ID，只读取当前有效关联
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR);
        validateId(id);
        SessionPaper relation = sessionPaperMapper.selectById(id);
        ThrowUtils.throwIf(relation == null, ErrorCode.NOT_FOUND_ERROR, "论文关联不存在");

        //2、校验聊天室归属，禁止读取其他用户的论文关联
        chatSessionService.getSession(relation.getSessionId(), userId);
        return relation;
    }

    @Override
    public Page<SessionPaper> listSessionPapers(SessionPaperQueryRequest request, Long userId) {
        //1、校验分页参数与聊天室归属
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(request.getPageNum() < 1 || request.getPageSize() < 1
                || request.getPageSize() > 100, ErrorCode.PARAMS_ERROR, "页码至少为1，每页数量为1至100");
        if (request.getPaperId() != null) {
            validateId(request.getPaperId());
        }
        chatSessionService.getSession(request.getSessionId(), userId);

        //2、仅返回当前仍在会话内的论文关联，使用固定顺序分页
        QueryWrapper<SessionPaper> query = new QueryWrapper<SessionPaper>()
                .eq("session_id", request.getSessionId())
                .eq(request.getPaperId() != null, "paper_id", request.getPaperId())
                .orderByDesc("create_time", "id");
        return sessionPaperMapper.selectPage(new Page<>(request.getPageNum(), request.getPageSize()), query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateSessionPaper(SessionPaperUpdateRequest request, Long userId) {
        //1、校验状态，只允许在移出和恢复之间切换
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        validateId(request.getId());
        ThrowUtils.throwIf(request.getIsDelete() == null
                || (request.getIsDelete() != 0 && request.getIsDelete() != 1),
                ErrorCode.PARAMS_ERROR, "关联状态只能为0或1");

        //2、先定位聊天室，再按聊天室、论文关联的顺序加锁并读取最新状态
        SessionPaper relation = sessionPaperMapper.selectIncludingRemoved(request.getId());
        ThrowUtils.throwIf(relation == null, ErrorCode.NOT_FOUND_ERROR, "论文关联不存在");
        ChatSession session = chatSessionService.lockSession(relation.getSessionId(), userId);
        SessionPaper current = sessionPaperMapper.selectPairForUpdate(relation.getSessionId(), relation.getPaperId());
        ThrowUtils.throwIf(current == null, ErrorCode.NOT_FOUND_ERROR, "论文关联不存在");

        //3、恢复时校验论文归属和有效性，相同状态不修改范围版本
        if (request.getIsDelete() == 0) {
            requireOwnedPaper(current.getPaperId(), userId);
        }
        if (request.getIsDelete().equals(current.getIsDelete())) {
            return true;
        }

        //4、原子更新关联状态、论文范围版本和最近活动时间
        requireChanged(sessionPaperMapper.updateMembership(current.getId(), request.getIsDelete()));
        requireChanged(chatSessionMapper.incrementScopeVersion(current.getSessionId(), userId));
        recordScopeChange(session, current.getPaperId(), request.getIsDelete() == 0);
        return true;
    }

    private void recordScopeChange(ChatSession session, Long paperId, boolean added) {
        //1、在集合实际变更后读取完整范围，反推变更前集合并记录版本
        var after = sessionPaperMapper.selectList(new QueryWrapper<SessionPaper>().eq("session_id", session.getId())
                .orderByAsc("paper_id")).stream().map(SessionPaper::getPaperId).toList();
        var before = new ArrayList<>(after);
        if (added) before.remove(paperId);
        else if (!before.contains(paperId)) before.add(paperId);
        before.sort(Long::compareTo);
        Paper paper = paperMapper.selectById(paperId);
        String title = paper == null ? "论文 " + paperId : paper.getTitle();

        //2、消息与关联、版本更新处于同一事务，不创建 Run，不改变活动任务快照
        ChatMessage message = new ChatMessage();
        message.setSessionId(session.getId()); message.setRole("system"); message.setMessageType("scope_change");
        message.setContent((added ? "已添加资料：《" : "已移除资料：《") + title + "》");
        message.setExtraData(ragJson.write(Map.of("beforePaperIds", before, "afterPaperIds", after,
                "scopeVersion", session.getScopeVersion() + 1, "paperId", paperId, "action", added ? "added" : "removed")));
        message.setIsDelete((byte) 0);
        requireChanged(chatMessageMapper.insert(message));
    }

    private void requireOwnedPaper(Long paperId, Long userId) {
        //1、新增或恢复关联时，按禁止选择已回收论文的规则校验
        requireOwnedPaper(paperId, userId, false);
    }

    private void requireOwnedPaper(Long paperId, Long userId, boolean alreadyAssociated) {
        //1、锁定论文并校验归属，使新增关联与回收操作串行执行，逻辑删除由框架过滤
        Paper paper = paperMapper.selectOne(new QueryWrapper<Paper>()
                .eq("id", paperId).eq("user_id", userId).last("FOR UPDATE"));
        ThrowUtils.throwIf(paper == null, ErrorCode.NOT_FOUND_ERROR, "论文不存在或无权访问");

        //2、已有会话关联允许保留回收论文，新增或恢复关联必须先恢复论文
        ThrowUtils.throwIf(!alreadyAssociated && Byte.valueOf((byte) 1).equals(paper.getIsRecycled()), ErrorCode.CONFLICT_ERROR,
                "该论文已回收，请恢复后再加入会话");
    }

    private void validateId(Long id) {
        //1、ID必须为正数
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "ID不合法");
    }

    private void requireChanged(int rows) {
        //1、确保单条记录更新成功，失败时抛出异常回滚事务
        ThrowUtils.throwIf(rows != 1, ErrorCode.OPERATION_ERROR, "更新论文关联失败");
    }
}
