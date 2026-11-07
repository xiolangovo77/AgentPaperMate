package com.ecnu.agentpapermate.service;

import com.ecnu.agentpapermate.entity.model.ChatSession;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.mapper.ChatSessionMapper;
import com.ecnu.agentpapermate.service.impl.ChatSessionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatSessionDeletionTest {
    private final ChatSessionMapper mapper = mock(ChatSessionMapper.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    private final ChatSession session = new ChatSession();
    private ChatSessionService service;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(transaction);
        var proxy = new ProxyFactory(new ChatSessionServiceImpl(mapper));
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (ChatSessionService) proxy.getProxy();
        session.setId(100L);
        session.setUserId(42L);
        when(mapper.selectOwnedForUpdate(100L, 42L)).thenReturn(session);
        when(mapper.logicalDeleteIdleSession(100L, 42L)).thenReturn(1);
    }

    @Test
    void deletesEmptySessionAndAllAssociationsInOneTransaction() {
        // 子表影响零行合法，空会话同样可以删除。
        assertTrue(service.deleteSession(100L, 42L));
        var order = inOrder(transactions, mapper);
        order.verify(transactions).getTransaction(any());
        order.verify(mapper).selectOwnedForUpdate(100L, 42L);
        order.verify(mapper).logicalDeleteCitations(100L);
        order.verify(mapper).logicalDeleteRunEvents(100L);
        order.verify(mapper).logicalDeleteMessages(100L);
        order.verify(mapper).logicalDeleteRuns(100L);
        order.verify(mapper).logicalDeleteSessionPapers(100L);
        order.verify(mapper).logicalDeleteIdleSession(100L, 42L);
        order.verify(transactions).commit(transaction);
        verify(transactions, never()).rollback(any());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void acceptsMultipleAffectedRowsInChildTables() {
        when(mapper.logicalDeleteCitations(100L)).thenReturn(15);
        when(mapper.logicalDeleteRunEvents(100L)).thenReturn(200);
        when(mapper.logicalDeleteMessages(100L)).thenReturn(20);
        when(mapper.logicalDeleteRuns(100L)).thenReturn(8);
        when(mapper.logicalDeleteSessionPapers(100L)).thenReturn(3);
        assertTrue(service.deleteSession(100L, 42L));
        verify(transactions).commit(transaction);
    }

    @Test
    void activeRunRejectsDeletionBeforeAnyWrites() {
        session.setActiveRunId(500L);
        assertError(ErrorCode.CONFLICT_ERROR, () -> service.deleteSession(100L, 42L));
        verify(mapper).selectOwnedForUpdate(100L, 42L);
        verifyNoMoreInteractions(mapper);
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
    }

    @Test
    void foreignMissingAndAlreadyDeletedSessionsCannotBeDeleted() {
        assertError(ErrorCode.NOT_FOUND_ERROR, () -> service.deleteSession(100L, 99L));
        assertError(ErrorCode.NOT_FOUND_ERROR, () -> service.deleteSession(999L, 42L));
        when(mapper.selectOwnedForUpdate(100L, 42L)).thenReturn(null);
        assertError(ErrorCode.NOT_FOUND_ERROR, () -> service.deleteSession(100L, 42L));
        verify(mapper).selectOwnedForUpdate(100L, 99L);
        verify(mapper).selectOwnedForUpdate(999L, 42L);
        verify(mapper).selectOwnedForUpdate(100L, 42L);
        verifyNoMoreInteractions(mapper);
        verify(transactions, never()).commit(any());
    }

    @Test
    void invalidIdentityNeverAccessesDatabase() {
        assertError(ErrorCode.NOT_LOGIN_ERROR, () -> service.deleteSession(100L, null));
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertError(ErrorCode.PARAMS_ERROR, () -> service.deleteSession(id, 42L));
        }
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"logicalDeleteCitations", "logicalDeleteRunEvents", "logicalDeleteMessages",
            "logicalDeleteRuns", "logicalDeleteSessionPapers", "logicalDeleteIdleSession"})
    void failureAtAnyDeleteStepRollsBack(String failingMethod) {
        ChatSessionMapper failingMapper = mock(ChatSessionMapper.class, call -> {
            if (call.getMethod().getName().equals("selectOwnedForUpdate")) return session;
            if (call.getMethod().getName().equals(failingMethod)) {
                throw new DataAccessResourceFailureException("test failure");
            }
            return 1;
        });
        var proxy = new ProxyFactory(new ChatSessionServiceImpl(failingMapper));
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        var failingService = (ChatSessionService) proxy.getProxy();
        assertThrows(DataAccessResourceFailureException.class, () -> failingService.deleteSession(100L, 42L));
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
        if (!failingMethod.equals("logicalDeleteIdleSession")) {
            verify(failingMapper, never()).logicalDeleteIdleSession(100L, 42L);
        }
    }

    @Test
    void failedFinalConditionalUpdateRollsBackChildDeletes() {
        when(mapper.logicalDeleteIdleSession(100L, 42L)).thenReturn(0);
        assertError(ErrorCode.OPERATION_ERROR, () -> service.deleteSession(100L, 42L));
        verify(mapper).logicalDeleteCitations(100L);
        verify(mapper).logicalDeleteRunEvents(100L);
        verify(mapper).logicalDeleteMessages(100L);
        verify(mapper).logicalDeleteRuns(100L);
        verify(mapper).logicalDeleteSessionPapers(100L);
        verify(transactions).rollback(transaction);
        verify(transactions, never()).commit(any());
    }

    private void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected.getCode(), assertThrows(BusinessException.class, action).getCode());
    }
}
