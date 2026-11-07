package com.ecnu.agentpapermate.controller;

import com.ecnu.agentpapermate.entity.model.AppUser;
import com.ecnu.agentpapermate.exception.BusinessException;
import com.ecnu.agentpapermate.exception.ErrorCode;
import com.ecnu.agentpapermate.exception.GlobalExceptionHandler;
import com.ecnu.agentpapermate.service.AppUserService;
import com.ecnu.agentpapermate.service.ChatSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatSessionControllerTest {
    private final ChatSessionService sessions = mock(ChatSessionService.class);
    private final AppUserService users = mock(AppUserService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ChatSessionController(sessions, users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var user = new AppUser();
        user.setId(42L);
        when(users.getLoginUser(any())).thenReturn(user);
    }

    @Test
    void deletionUsesAuthenticatedOwnerAndReturnsBoolean() throws Exception {
        when(sessions.deleteSession(100L, 42L)).thenReturn(true);
        mvc.perform(post("/chatSession/delete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"100\",\"userId\":99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));
        verify(sessions).deleteSession(100L, 42L);
    }

    @Test
    void unauthenticatedDeletionNeverCallsService() throws Exception {
        when(users.getLoginUser(any())).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        mvc.perform(post("/chatSession/delete").contentType(MediaType.APPLICATION_JSON).content("{\"id\":100}"))
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN_ERROR.getCode()));
        verifyNoInteractions(sessions);
    }

    @Test
    void activeRunConflictUsesExistingBusinessResponse() throws Exception {
        when(sessions.deleteSession(100L, 42L)).thenThrow(new BusinessException(ErrorCode.CONFLICT_ERROR));
        mvc.perform(post("/chatSession/delete").contentType(MediaType.APPLICATION_JSON).content("{\"id\":100}"))
                .andExpect(jsonPath("$.code").value(ErrorCode.CONFLICT_ERROR.getCode()));
    }
}
