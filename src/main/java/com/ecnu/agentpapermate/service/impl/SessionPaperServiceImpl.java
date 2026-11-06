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

    private final SessionPaperMapper sessionPaperMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final PaperMapper paperMapper;
    private final ChatSessionService chatSessionService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addSessionPaper(SessionPaperAddRequest request, Long userId) {
        //1、校验请求并锁定所属聊天室，避免并发重复添加或丢失版本更新
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        validateId(request.getPaperId());
        chatSessionService.lockSession(request.getSessionId(), userId);
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
        chatSessionService.lockSession(relation.getSessionId(), userId);
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
        return true;
    }

    private void requireOwnedPaper(Long paperId, Long userId) {
        //1、论文必须存在、未删除且属于当前用户
        Paper paper = paperMapper.selectOne(new QueryWrapper<Paper>()
                .eq("id", paperId).eq("user_id", userId));
        ThrowUtils.throwIf(paper == null, ErrorCode.NOT_FOUND_ERROR, "论文不存在或无权访问");
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
