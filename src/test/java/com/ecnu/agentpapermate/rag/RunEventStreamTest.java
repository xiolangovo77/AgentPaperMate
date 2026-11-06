package com.ecnu.agentpapermate.rag;

import com.ecnu.agentpapermate.entity.vo.run.EventView;
import com.ecnu.agentpapermate.config.rag.RagProperties;
import com.ecnu.agentpapermate.controller.AgentRunController;
import com.ecnu.agentpapermate.entity.model.AgentRun;
import com.ecnu.agentpapermate.entity.model.AppUser;
import com.ecnu.agentpapermate.exception.*;
import com.ecnu.agentpapermate.service.AgentRunService;
import com.ecnu.agentpapermate.service.AppUserService;
import com.ecnu.agentpapermate.service.run.rag.RunEventStream;
import com.ecnu.agentpapermate.service.run.rag.RunQueries;
import com.ecnu.agentpapermate.service.run.rag.RunStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RunEventStreamTest {
    private final RunStore store = mock(RunStore.class);
    private final RunQueries queries = mock(RunQueries.class);
    private final AppUserService users = mock(AppUserService.class);
    private ExecutorService executor;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        var stream = new RunEventStream(queries, store, executor, new RagProperties());
        var controller = new AgentRunController(mock(AgentRunService.class), users, stream);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules())).build();
        var user = new AppUser(); user.setId(20L);
        when(users.getLoginUser(any())).thenReturn(user);
        var run = new AgentRun(); run.setId(40L); run.setStatus("completed"); run.setEventSeq(2L);
        when(store.owned(40L, 20L)).thenReturn(run);
    }

    @AfterEach
    void close() { executor.shutdownNow(); }

    @Test
    void reconnectUsesLastEventIdAndReplaysOnlyCommittedLaterEvents() throws Exception {
        var payload = new ObjectMapper().readTree("{\"status\":\"completed\"}");
        when(queries.events(40L, 1L, 20L)).thenReturn(List.of(
                new EventView(40L, 2L, "run_completed", LocalDateTime.of(2026, 11, 6, 10, 0), payload)));
        var result = mvc.perform(get("/agentRun/events").param("id", "40").param("afterEventSeq", "0")
                        .header("Last-Event-ID", "1"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(3000);
        String body = mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Buffering", "no")).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("id:2")); assertTrue(body.contains("event:run_completed"));
        assertFalse(body.contains("id:1"));
        verify(queries).validateCursor(eq(1L), any());
        verify(store, never()).cancel(anyLong(), anyLong());
    }

    @Test
    void unauthorizedSubscriptionReturnsJsonBeforeOpeningStream() throws Exception {
        when(store.owned(40L, 20L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR, "任务不存在或无权访问"));
        mvc.perform(get("/agentRun/events").param("id", "40"))
                .andExpect(status().isOk()).andExpect(request().asyncNotStarted()).andExpect(jsonPath("$.code").value(40400));
        verify(queries, never()).events(anyLong(), anyLong(), anyLong());
    }

    @Test
    void invalidReconnectCursorIsRejectedBeforeSubscription() throws Exception {
        mvc.perform(get("/agentRun/events").param("id", "40").header("Last-Event-ID", "invalid"))
                .andExpect(status().isOk()).andExpect(request().asyncNotStarted()).andExpect(jsonPath("$.code").value(40000));
        verifyNoInteractions(store);
    }
}
