package com.ecnu.agentpapermate.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.util.Map;
import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.mapping.ParameterMapping;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RunMapperConfigurationTest {
    @Test
    void runLockAndSnowflakeEntitiesLoadWithLogicalDeletion() throws Exception {
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(mock(DataSource.class)); bean.setConfiguration(new MybatisConfiguration());
        bean.setMapperLocations(new ClassPathResource("mapper/AgentRunMapper.xml"), new ClassPathResource("mapper/ChatMessageMapper.xml"),
                new ClassPathResource("mapper/MessageCitationMapper.xml"), new ClassPathResource("mapper/RunEventMapper.xml"),
                new ClassPathResource("mapper/SessionPaperMapper.xml"), new ClassPathResource("mapper/ChatSessionMapper.xml"));
        var configuration = bean.getObject().getConfiguration();
        String prefix = "com.ecnu.agentpapermate.mapper.";
        String sql = configuration.getMappedStatement(prefix + "AgentRunMapper.selectForUpdate").getBoundSql(Map.of("id", 1L)).getSql();
        assertTrue(sql.contains("FOR UPDATE")); assertTrue(sql.contains("is_delete = 0"));
        for (String mapper : new String[]{"AgentRunMapper", "ChatMessageMapper", "MessageCitationMapper", "RunEventMapper"}) {
            assertTrue(configuration.hasStatement(prefix + mapper + ".insert"));
            assertTrue(configuration.hasStatement(prefix + mapper + ".updateById"));
        }
        //1、自定义 XML 不会自动附加逻辑删除条件，检查查询范围与参数绑定
        var now = LocalDateTime.of(2026, 11, 6, 10, 0);
        var expired = configuration.getMappedStatement(prefix + "AgentRunMapper.selectExpiredCandidates")
                .getBoundSql(Map.of("now", now, "queuedBefore", now.minusSeconds(300), "limit", 100));
        String expiredSql = expired.getSql().replaceAll("\\s+", " ").strip();
        assertTrue(expiredSql.contains("WHERE is_delete = 0 AND ( (status = 'running' AND deadline <= ?) OR (status = 'pending' AND create_time <= ?) )"));
        assertTrue(expiredSql.endsWith("ORDER BY id ASC LIMIT ?"));
        assertEquals(List.of("now", "queuedBefore", "limit"),
                expired.getParameterMappings().stream().map(ParameterMapping::getProperty).toList());

        for (String statement : List.of("AgentRunMapper.selectPendingCandidates", "RunEventMapper.selectAfterSequence",
                "ChatMessageMapper.selectPreviousQuestions", "SessionPaperMapper.selectActiveForRun")) {
            var bound = configuration.getMappedStatement(prefix + statement).getBoundSql(Map.of(
                    "runId", 40L, "sessionId", 10L, "beforeRunId", 40L, "after", 2L, "limit", 6));
            assertTrue(bound.getSql().contains("is_delete = 0"), statement);
            assertTrue(bound.getSql().contains("LIMIT ?"), statement);
            assertEquals("limit", bound.getParameterMappings().getLast().getProperty(), statement);
        }
        //2、释放会话仍需匹配当前任务，不能因迁移 SQL 而清除其他任务的占用
        String releaseSql = configuration.getMappedStatement(prefix + "ChatSessionMapper.releaseActiveRun")
                .getBoundSql(Map.of("sessionId", 10L, "runId", 40L)).getSql().replaceAll("\\s+", " ");
        assertTrue(releaseSql.contains("WHERE id = ? AND active_run_id = ? AND is_delete = 0"));
        assertTrue(configuration.hasStatement(prefix + "ChatSessionMapper.assignActiveRun"));
    }
}
