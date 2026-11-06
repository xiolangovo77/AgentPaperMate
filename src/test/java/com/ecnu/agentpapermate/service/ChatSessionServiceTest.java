package com.ecnu.agentpapermate.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ecnu.agentpapermate.entity.dto.chatSession.ChatSessionAddRequest;
import com.ecnu.agentpapermate.entity.dto.chatSession.ChatSessionQueryRequest;
import com.ecnu.agentpapermate.entity.dto.chatSession.ChatSessionUpdateRequest;
import com.ecnu.agentpapermate.entity.model.ChatSession;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.mapper.ChatSessionMapper;
import com.ecnu.agentpapermate.service.impl.ChatSessionServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatSessionServiceTest {
    private final ChatSessionMapper mapper = mock(ChatSessionMapper.class);
    private final ChatSessionServiceImpl service = new ChatSessionServiceImpl(mapper);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    void failedSaveAndDuplicateIdProduceBusinessErrors() {
        when(mapper.insert(any(ChatSession.class))).thenReturn(0);
        BusinessException failed = assertThrows(BusinessException.class,
                () -> service.addSession(new ChatSessionAddRequest(), 42L));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), failed.getCode());
        assertEquals("新增聊天室失败", failed.getMessage());

        when(mapper.insert(any(ChatSession.class))).thenThrow(new DuplicateKeyException("duplicate id"));
        BusinessException duplicate = assertThrows(BusinessException.class,
                () -> service.addSession(new ChatSessionAddRequest(), 42L));
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), duplicate.getCode());
        assertEquals("聊天室ID冲突，请重试", duplicate.getMessage());
    }

    @Test
    void createsDefaultSessionUsingAuthenticatedOwner() {
        when(mapper.insert(any(ChatSession.class))).thenAnswer(call -> {
            call.<ChatSession>getArgument(0).setId(100L);
            return 1;
        });
        assertEquals(100L, service.addSession(new ChatSessionAddRequest(), 42L));
        var captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(mapper).insert(captor.capture());
        ChatSession created = captor.getValue();
        assertEquals(42L, created.getUserId());
        assertEquals("新研究会话", created.getName());
        assertEquals(1, created.getScopeVersion());
        assertEquals((byte) 0, created.getIsDelete());
        assertNull(created.getLastMessageTime());
        assertNull(created.getActiveRunId());
        assertNull(created.getCreateTime());
    }

    @Test
    void rejectsBlankOrLongNamesAndOversizedUtf8DraftsBeforeWriting() {
        var request = new ChatSessionAddRequest();
        for (String name : new String[]{" ", "字".repeat(201)}) {
            request.setName(name);
            assertError(ErrorCode.PARAMS_ERROR, () -> service.addSession(request, 42L));
        }
        request.setName("研究");
        request.setInputDraft("字".repeat(21846));
        assertError(ErrorCode.PARAMS_ERROR, () -> service.addSession(request, 42L));
        assertError(ErrorCode.NOT_LOGIN_ERROR, () -> service.addSession(request, null));
        verifyNoInteractions(mapper);
    }

    @Test
    void clearingDraftDoesNotWriteActivityVersionOrRunFields() {
        ChatSession existing = session();
        existing.setInputDraft("原草稿");
        existing.setLastMessageTime(LocalDateTime.of(2026, 11, 6, 12, 0));
        when(mapper.selectOwnedForUpdate(100L, 42L)).thenReturn(existing);
        when(mapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        var request = new ChatSessionUpdateRequest();
        request.setId(100L);
        request.setInputDraft("");
        assertTrue(service.updateSession(request, 42L));

        var captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        String sql = captor.getValue().getSqlSet();
        assertTrue(sql.contains("input_draft"));
        assertTrue(sql.contains("update_time"));
        assertFalse(sql.contains("last_message_time"));
        assertFalse(sql.contains("scope_version"));
        assertFalse(sql.contains("active_run_id"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(""));
        verify(mapper, never()).incrementScopeVersion(anyLong(), anyLong());
    }

    @Test
    void rejectsForeignOrMissingSessionAndEmptyUpdates() {
        var request = new ChatSessionUpdateRequest();
        request.setId(100L);
        assertError(ErrorCode.PARAMS_ERROR, () -> service.updateSession(request, 42L));
        request.setName("新名称");
        assertError(ErrorCode.NOT_FOUND_ERROR, () -> service.updateSession(request, 99L));
        verify(mapper, never()).update(isNull(), any(UpdateWrapper.class));
        assertError(ErrorCode.NOT_FOUND_ERROR, () -> service.getSession(100L, 99L));
        var query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectOne(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("user_id"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(99L));
    }

    @Test
    void sameContentUpdateIsIdempotent() {
        when(mapper.selectOwnedForUpdate(100L, 42L)).thenReturn(session());
        var request = new ChatSessionUpdateRequest();
        request.setId(100L);
        request.setName(" 研究 ");
        assertTrue(service.updateSession(request, 42L));
        verify(mapper, never()).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void paginationScopesOwnerUsesStableOrderAndCapsPageSize() {
        var request = new ChatSessionQueryRequest();
        request.setName("研究");
        request.setSortField("id; DROP TABLE chat_session");
        when(mapper.selectPage(any(Page.class), any())).thenReturn(new Page<ChatSession>());
        service.listSessions(request, 42L);
        var query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectPage(any(Page.class), query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("user_id"));
        assertTrue(sql.contains("ORDER BY last_message_time DESC,id DESC"));
        assertFalse(sql.contains("DROP"));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(42L));
        request.setPageSize(101);
        assertError(ErrorCode.PARAMS_ERROR, () -> service.listSessions(request, 42L));
    }

    private ChatSession session() {
        ChatSession session = new ChatSession();
        session.setId(100L);
        session.setUserId(42L);
        session.setName("研究");
        return session;
    }

    private void assertError(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected.getCode(), assertThrows(BusinessException.class, action).getCode());
    }
}
