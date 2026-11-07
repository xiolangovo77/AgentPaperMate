package com.ecnu.agentpapermate.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ChatSessionMapperConfigurationTest {
    @Test
    void customMappersLoadAlongsideLogicalDeleteStatements() throws Exception {
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(mock(DataSource.class));
        bean.setConfiguration(new MybatisConfiguration());
        bean.setMapperLocations(new ClassPathResource("mapper/ChatSessionMapper.xml"),
                new ClassPathResource("mapper/SessionPaperMapper.xml"));
        var configuration = bean.getObject().getConfiguration();
        String chat = "com.ecnu.agentpapermate.mapper.ChatSessionMapper.";
        String paper = "com.ecnu.agentpapermate.mapper.SessionPaperMapper.";
        for (String method : new String[]{"insert", "selectById", "update", "selectOwnedForUpdate", "incrementScopeVersion"}) {
            assertTrue(configuration.hasStatement(chat + method), method);
        }
        for (String method : new String[]{"insert", "selectById", "selectIncludingRemoved", "selectPairForUpdate", "updateMembership"}) {
            assertTrue(configuration.hasStatement(paper + method), method);
        }
        String active = configuration.getMappedStatement(paper + "selectById").getBoundSql(Map.of("id", 1L)).getSql();
        assertTrue(active.contains("is_delete=0"));
        String restore = configuration.getMappedStatement(paper + "updateMembership")
                .getBoundSql(Map.of("id", 1L, "isDelete", 0)).getSql();
        assertTrue(restore.contains("is_delete !="));
        assertFalse(restore.contains("is_delete=0"));
        String lock = configuration.getMappedStatement(chat + "selectOwnedForUpdate")
                .getBoundSql(Map.of("id", 1L, "userId", 2L)).getSql();
        assertTrue(lock.contains("user_id ="));
        assertTrue(lock.contains("FOR UPDATE"));

        for (String method : new String[]{"logicalDeleteCitations", "logicalDeleteRunEvents", "logicalDeleteMessages",
                "logicalDeleteRuns", "logicalDeleteSessionPapers", "logicalDeleteIdleSession"}) {
            var bound = configuration.getMappedStatement(chat + method)
                    .getBoundSql(Map.of("sessionId", 1L, "id", 1L, "userId", 2L));
            String sql = bound.getSql().replaceAll("\\s+", " ");
            assertTrue(sql.startsWith("UPDATE "), method);
            assertTrue(sql.contains("SET is_delete = 1, update_time = CURRENT_TIMESTAMP(3)"), method);
            assertTrue(sql.contains("is_delete = 0"), method);
            if (method.equals("logicalDeleteIdleSession")) {
                assertTrue(sql.contains("id = ? AND user_id = ?"));
                assertTrue(sql.contains("active_run_id IS NULL"));
            } else {
                assertTrue(sql.contains("session_id = ?"), method);
                assertEquals("sessionId", bound.getParameterMappings().getFirst().getProperty());
            }
            if (method.equals("logicalDeleteCitations")) {
                assertTrue(sql.contains("message_id IN (SELECT id FROM chat_message WHERE session_id = ?)"));
            }
            if (method.equals("logicalDeleteRunEvents")) {
                assertTrue(sql.contains("run_id IN (SELECT id FROM agent_run WHERE session_id = ?)"));
            }
        }
    }
}
