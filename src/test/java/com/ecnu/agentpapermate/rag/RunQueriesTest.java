package com.ecnu.agentpapermate.rag;

import com.ecnu.agentpapermate.agent.context.AgentRunContext;
import com.ecnu.agentpapermate.entity.model.ChatMessage;
import com.ecnu.agentpapermate.mapper.ChatMessageMapper;
import com.ecnu.agentpapermate.rag.model.ConversationMessage;
import com.ecnu.agentpapermate.service.run.rag.RunQueries;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunQueriesTest {
    private final ChatMessageMapper messages = mock(ChatMessageMapper.class);
    private final RunQueries queries = new RunQueries(null, null, messages, null, null, null);
    private final AgentRunContext context = mock(AgentRunContext.class);

    @Test
    void recentTwelveMessagesKeepBothRolesInChronologicalOrder() {
        when(context.getSessionId()).thenReturn(10L);
        when(context.getRunId()).thenReturn(40L);
        var recent = IntStream.range(0, 12)
                .mapToObj(i -> message(i % 2 == 0 ? "assistant" : "user", "消息" + (12 - i))).toList();
        when(messages.selectRecentMessages(10L, 40L, 12)).thenReturn(recent);

        var result = queries.recentMessages(context);

        assertEquals(12, result.size());
        assertEquals(new ConversationMessage("user", "消息1"), result.getFirst());
        assertEquals(new ConversationMessage("assistant", "消息12"), result.getLast());
        assertEquals(recent.reversed().stream()
                .map(item -> new ConversationMessage(item.getRole(), item.getContent())).toList(), result);
        verify(messages).selectRecentMessages(10L, 40L, 12);
    }

    @Test
    void historyRetainsExistingCharacterBudgetAndPrioritizesRecentMessages() {
        when(messages.selectRecentMessages(any(), any(), eq(12))).thenReturn(List.of(
                message("assistant", "答".repeat(3000)), message("user", "问".repeat(1000)),
                message("assistant", "较早的回答")));

        var result = queries.recentMessages(context);

        assertEquals(List.of(new ConversationMessage("user", "问".repeat(1000)),
                new ConversationMessage("assistant", "答".repeat(3000))), result);
    }

    private ChatMessage message(String role, String content) {
        var message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
