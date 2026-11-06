package com.ecnu.agentpapermate.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecnu.agentpapermate.entity.dto.sessionPaper.SessionPaperAddRequest;
import com.ecnu.agentpapermate.entity.dto.sessionPaper.SessionPaperQueryRequest;
import com.ecnu.agentpapermate.entity.dto.sessionPaper.SessionPaperUpdateRequest;
import com.ecnu.agentpapermate.entity.model.ChatSession;
import com.ecnu.agentpapermate.entity.model.Paper;
import com.ecnu.agentpapermate.entity.model.SessionPaper;
import com.ecnu.agentpapermate.entity.model.ChatMessage;
import com.ecnu.agentpapermate.mapper.ChatMessageMapper;
import com.ecnu.agentpapermate.support.json.RagJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.mapper.ChatSessionMapper;
import com.ecnu.agentpapermate.mapper.PaperMapper;
import com.ecnu.agentpapermate.mapper.SessionPaperMapper;
import com.ecnu.agentpapermate.service.impl.SessionPaperServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionPaperServiceTest {
    private final SessionPaperMapper relations = mock(SessionPaperMapper.class);
    private final ChatSessionMapper sessions = mock(ChatSessionMapper.class);
    private final PaperMapper papers = mock(PaperMapper.class);
    private final ChatSessionService chatService = mock(ChatSessionService.class);
    private final ChatMessageMapper messages = mock(ChatMessageMapper.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus txStatus = new SimpleTransactionStatus();
    private SessionPaperService service;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(txStatus);
        var target = new SessionPaperServiceImpl(relations, sessions, papers, chatService, messages, new RagJson(new ObjectMapper()));
        ReflectionTestUtils.setField(target, "baseMapper", relations);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (SessionPaperService) proxy.getProxy();
        var session = new ChatSession();
        session.setId(100L); session.setScopeVersion(1);
        when(chatService.lockSession(100L, 42L)).thenReturn(session);
        when(messages.insert(any(ChatMessage.class))).thenReturn(1);
        when(relations.selectList(any())).thenReturn(java.util.List.of(relation((byte) 0)));
        when(papers.selectOne(any())).thenReturn(new Paper());
        when(sessions.incrementScopeVersion(100L, 42L)).thenReturn(1);
        when(relations.updateMembership(anyLong(), anyByte())).thenReturn(1);
        when(relations.insert(any(SessionPaper.class))).thenAnswer(call -> {
            call.<SessionPaper>getArgument(0).setId(300L);
            return 1;
        });
    }

    @Test
    void addsRelationAfterSessionLockAndCommitsVersionInSameTransaction() {
        assertEquals(300L, service.addSessionPaper(addRequest(), 42L));
        var order = inOrder(transactions, chatService, relations, sessions, messages);
        order.verify(transactions).getTransaction(any());
        order.verify(chatService).lockSession(100L, 42L);
        order.verify(relations).selectPairForUpdate(100L, 200L);
        var captor = ArgumentCaptor.forClass(SessionPaper.class);
        order.verify(relations).insert(captor.capture());
        order.verify(sessions).incrementScopeVersion(100L, 42L);
        var message = ArgumentCaptor.forClass(ChatMessage.class);
        order.verify(messages).insert(message.capture());
        order.verify(transactions).commit(txStatus);
        assertEquals(100L, captor.getValue().getSessionId());
        assertEquals(200L, captor.getValue().getPaperId());
        assertEquals((byte) 0, captor.getValue().getIsDelete());
        assertNull(captor.getValue().getCreateTime());
        assertEquals("system", message.getValue().getRole());
        assertEquals("scope_change", message.getValue().getMessageType());
        assertNull(message.getValue().getRunId());
        assertTrue(message.getValue().getExtraData().contains("\"scopeVersion\":2"));
    }

    @Test
    void duplicateAddDoesNotWriteOrAdvanceVersion() {
        when(relations.selectPairForUpdate(100L, 200L)).thenReturn(relation((byte) 0));
        assertEquals(300L, service.addSessionPaper(addRequest(), 42L));
        verify(relations, never()).insert(any(SessionPaper.class));
        verify(relations, never()).updateMembership(anyLong(), anyByte());
        verifyNoInteractions(sessions);
        verifyNoInteractions(messages);
    }

    @Test
    void reAddingRemovedPaperRestoresOriginalRecord() {
        when(relations.selectPairForUpdate(100L, 200L)).thenReturn(relation((byte) 1));
        assertEquals(300L, service.addSessionPaper(addRequest(), 42L));
        verify(relations).updateMembership(300L, (byte) 0);
        verify(relations, never()).insert(any(SessionPaper.class));
        verify(sessions).incrementScopeVersion(100L, 42L);
    }

    @Test
    void removalAndRestoreAdvanceVersionOnlyWhenStateChanges() {
        when(relations.selectIncludingRemoved(300L)).thenReturn(relation((byte) 0));
        when(relations.selectPairForUpdate(100L, 200L))
                .thenReturn(relation((byte) 0), relation((byte) 1), relation((byte) 1));
        assertTrue(service.updateSessionPaper(updateRequest((byte) 1), 42L));
        assertTrue(service.updateSessionPaper(updateRequest((byte) 1), 42L));
        assertTrue(service.updateSessionPaper(updateRequest((byte) 0), 42L));
        verify(relations).updateMembership(300L, (byte) 1);
        verify(relations).updateMembership(300L, (byte) 0);
        verify(sessions, times(2)).incrementScopeVersion(100L, 42L);
    }

    @Test
    void versionUpdateFailureRollsBackRelationWrite() {
        when(sessions.incrementScopeVersion(100L, 42L)).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.addSessionPaper(addRequest(), 42L));
        verify(relations).insert(any(SessionPaper.class));
        verify(transactions).rollback(txStatus);
        verify(transactions, never()).commit(any());
    }

    @Test
    void scopeMessageFailureRollsBackMembershipAndVersion() {
        when(messages.insert(any(ChatMessage.class))).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.addSessionPaper(addRequest(), 42L));
        verify(sessions).incrementScopeVersion(100L, 42L);
        verify(transactions).rollback(txStatus);
        verify(transactions, never()).commit(any());
    }

    @Test
    void failedRelationWriteNeverAdvancesVersion() {
        when(relations.insert(any(SessionPaper.class))).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.addSessionPaper(addRequest(), 42L));
        verifyNoInteractions(sessions);
        verify(transactions).rollback(txStatus);
    }

    @Test
    void duplicateKeyBecomesBusinessErrorAndRollsBackWithoutAdvancingVersion() {
        when(relations.insert(any(SessionPaper.class))).thenThrow(new DuplicateKeyException("duplicate pair"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.addSessionPaper(addRequest(), 42L));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), error.getCode());
        assertEquals("论文关联记录冲突，请刷新后重试", error.getMessage());
        verifyNoInteractions(sessions);
        verify(transactions).rollback(txStatus);
        verify(transactions, never()).commit(any());
    }

    @Test
    void foreignSessionIsRejectedBeforePaperAccessOrMutation() {
        when(chatService.lockSession(100L, 99L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR));
        assertThrows(BusinessException.class, () -> service.addSessionPaper(addRequest(), 99L));
        verifyNoInteractions(relations, papers, sessions);
    }

    @Test
    void foreignOrDeletedPaperCannotBeAddedOrRestored() {
        when(papers.selectOne(any())).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.addSessionPaper(addRequest(), 42L));
        when(relations.selectIncludingRemoved(300L)).thenReturn(relation((byte) 1));
        when(relations.selectPairForUpdate(100L, 200L)).thenReturn(relation((byte) 1));
        assertThrows(BusinessException.class, () -> service.updateSessionPaper(updateRequest((byte) 0), 42L));
        verify(relations, never()).insert(any(SessionPaper.class));
        verify(relations, never()).updateMembership(anyLong(), anyByte());
        verifyNoInteractions(sessions);
        var query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(papers, times(2)).selectOne(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("user_id"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(42L));
    }

    @Test
    void rejectsInvalidMembershipState() {
        assertThrows(BusinessException.class, () -> service.updateSessionPaper(updateRequest((byte) 2), 42L));
        verifyNoInteractions(relations, chatService, sessions, papers);
    }

    @Test
    void detailAndListRequireOwnedSession() {
        when(relations.selectById(300L)).thenReturn(relation((byte) 0));
        when(chatService.getSession(100L, 99L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR));
        assertThrows(BusinessException.class, () -> service.getSessionPaper(300L, 99L));
        var request = new SessionPaperQueryRequest();
        request.setSessionId(100L);
        assertThrows(BusinessException.class, () -> service.listSessionPapers(request, 99L));
        verify(relations, never()).selectPage(any(Page.class), any());
        request.setPageSize(101);
        assertThrows(BusinessException.class, () -> service.listSessionPapers(request, 42L));
    }

    private SessionPaper relation(byte removed) {
        var relation = new SessionPaper();
        relation.setId(300L);
        relation.setSessionId(100L);
        relation.setPaperId(200L);
        relation.setIsDelete(removed);
        return relation;
    }

    private SessionPaperAddRequest addRequest() {
        var request = new SessionPaperAddRequest();
        request.setSessionId(100L);
        request.setPaperId(200L);
        return request;
    }

    private SessionPaperUpdateRequest updateRequest(byte removed) {
        var request = new SessionPaperUpdateRequest();
        request.setId(300L);
        request.setIsDelete(removed);
        return request;
    }
}
