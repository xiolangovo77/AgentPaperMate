package com.ecnu.agentpapermate.service;

import com.ecnu.agentpapermate.entity.model.ChatSession;
import com.baomidou.mybatisplus.spring.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecnu.agentpapermate.entity.dto.chatSession.ChatSessionAddRequest;
import com.ecnu.agentpapermate.entity.dto.chatSession.ChatSessionQueryRequest;
import com.ecnu.agentpapermate.entity.dto.chatSession.ChatSessionUpdateRequest;

/**
 * <p>
 * 研究聊天室 服务类
 * </p>
 *
 * @author wolf_cub
 * @since 2026-11-06
 */
public interface ChatSessionService extends IService<ChatSession> {
    /** 创建当前用户的聊天室。 */
    Long addSession(ChatSessionAddRequest request, Long userId);

    /** 查询当前用户的聊天室详情。 */
    ChatSession getSession(Long id, Long userId);

    /** 分页查询当前用户的聊天室。 */
    Page<ChatSession> listSessions(ChatSessionQueryRequest request, Long userId);

    /** 更新名称或草稿，不改变论文范围版本及最近活动时间。 */
    boolean updateSession(ChatSessionUpdateRequest request, Long userId);

    /** 删除没有活动任务的聊天室及其关联记录，保留论文和阅读笔记。 */
    boolean deleteSession(Long id, Long userId);

    /** 锁定当前用户的聊天室，调用方必须处于事务中。 */
    ChatSession lockSession(Long id, Long userId);
}
