package com.ecnu.agentpapermate.service;

import com.ecnu.agentpapermate.entity.model.SessionPaper;
import com.baomidou.mybatisplus.spring.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecnu.agentpapermate.entity.dto.sessionPaper.SessionPaperAddRequest;
import com.ecnu.agentpapermate.entity.dto.sessionPaper.SessionPaperQueryRequest;
import com.ecnu.agentpapermate.entity.dto.sessionPaper.SessionPaperUpdateRequest;
import com.ecnu.agentpapermate.entity.dto.sessionPaper.SessionPaperScopeRequest;
import com.ecnu.agentpapermate.entity.vo.sessionPaper.SessionPaperScopeVO;

/**
 * <p>
 * 聊天室当前论文关联 服务类
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
public interface SessionPaperService extends IService<SessionPaper> {
    /** 校验范围版本后在同一事务中替换完整论文集合。 */
    SessionPaperScopeVO updateScope(SessionPaperScopeRequest request, Long userId);

    /** 添加或恢复论文关联，重复添加保持幂等。 */
    Long addSessionPaper(SessionPaperAddRequest request, Long userId);

    /** 查询当前用户的有效论文关联。 */
    SessionPaper getSessionPaper(Long id, Long userId);

    /** 分页查询聊天室中的有效论文关联。 */
    Page<SessionPaper> listSessionPapers(SessionPaperQueryRequest request, Long userId);

    /** 移出或恢复论文关联，实际变化时更新论文范围版本。 */
    boolean updateSessionPaper(SessionPaperUpdateRequest request, Long userId);
}
