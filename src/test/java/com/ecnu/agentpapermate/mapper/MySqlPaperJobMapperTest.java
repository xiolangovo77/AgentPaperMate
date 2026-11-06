package com.ecnu.agentpapermate.mapper;

import com.ecnu.agentpapermate.ingestion.model.ParsedPaper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 显式提供测试连接后运行；仅操作会话临时表，连接关闭即删除，不操作永久业务表。 */
@EnabledIfEnvironmentVariable(named = "PAPER_JOB_TEST_JDBC_URL", matches = ".+")
class MySqlPaperJobMapperTest {
    private Connection connection;
    private SqlSession session;
    private PaperProcessingJobMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(System.getenv("PAPER_JOB_TEST_JDBC_URL"),
                System.getenv("PAPER_JOB_TEST_USERNAME"), System.getenv("PAPER_JOB_TEST_PASSWORD"));
        execute("SET time_zone = '+08:00'");
        String ddl = Files.readString(Path.of("sql/create_table.sql"));
        for (String table : new String[]{"paper", "paper_processing_job"}) {
            var match = Pattern.compile("CREATE TABLE " + table + " \\([\\s\\S]*?;").matcher(ddl);
            assertTrue(match.find());
            execute(match.group().replaceFirst("CREATE TABLE", "CREATE TEMPORARY TABLE"));
        }
        connection.setAutoCommit(false);
        Configuration configuration = new Configuration();
        try (var input = Files.newInputStream(Path.of("src/main/resources/mapper/PaperProcessingJobMapper.xml"))) {
            new XMLMapperBuilder(input, configuration, "PaperProcessingJobMapper.xml", configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(connection);
        mapper = session.getMapper(PaperProcessingJobMapper.class);
        execute("INSERT INTO paper (id,user_id,title,original_filename,file_hash,file_size,file_url) "
                + "VALUES (1,1,'test','test.pdf',REPEAT('a',64),1,'test.pdf')");
        execute("INSERT INTO paper_processing_job (id,paper_id,index_version,parser_version,chunking_version,"
                + "chunking_config,embedding_model,embedding_version,embedding_dimension,es_index_name) "
                + "VALUES (1,1,1,'test','test','{}','test','test',1,'test')");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (session != null) session.close();
        if (connection != null && !connection.isClosed()) connection.close();
    }

    @Test
    void claimUsesDueTimeAndExcludesDeletedPapers() throws Exception {
        execute("UPDATE paper_processing_job SET next_retry_time = TIMESTAMPADD(HOUR,1,CURRENT_TIMESTAMP(3))");
        assertNull(mapper.selectDueForUpdate());
        execute("UPDATE paper_processing_job SET next_retry_time = TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP(3))");
        execute("UPDATE paper SET is_delete = 1");
        assertNull(mapper.selectDueForUpdate());
        execute("UPDATE paper SET is_delete = 0");
        assertEquals(1L, mapper.selectDueForUpdate().getId());
        assertEquals(1, mapper.claim(1L, "lease-a", 120));
        assertEquals(0, mapper.claim(1L, "lease-b", 120));
        assertNull(mapper.selectDueForUpdate());
    }

    @Test
    void completionChecksLeaseAndAtomicallyPublishesAttempt() throws Exception {
        mapper.claim(1L, "lease-a", 120);
        var parsed = new ParsedPaper("Title", "Author", "Abstract", 3, java.util.List.of());
        assertEquals(0, mapper.complete(claimed("stale"), parsed, 2, 1800));
        assertTrue(mapper.complete(claimed("lease-a"), parsed, 2, 1800) > 0);
        assertEquals("completed", field("status"));
        assertEquals("2", field("indexed_chunks"));
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT status,active_index_attempt FROM paper WHERE id=1")) {
            assertTrue(result.next());
            assertEquals("ready", result.getString(1));
            assertEquals("lease-a", result.getString(2));
        }
    }

    @Test
    void metadataPersistsBeforeCompletionAndUncertainAuthorsDoNotOverwrite() throws Exception {
        mapper.claim(1L, "lease-a", 120);
        execute("UPDATE paper SET authors='Existing Author', page_count=10");
        var parsed = new ParsedPaper("Parsed title", "Uncertain authors", "Abstract", null,
                java.util.List.of(), false);
        assertEquals(0, mapper.updateParsedMetadata(claimed("stale"), parsed));
        assertTrue(mapper.updateParsedMetadata(claimed("lease-a"), parsed) > 0);
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT title,authors,page_count,status FROM paper WHERE id=1")) {
            assertTrue(result.next());
            assertEquals("Parsed title", result.getString(1));
            assertEquals("Existing Author", result.getString(2));
            assertEquals(10, result.getInt(3));
            assertNotEquals("ready", result.getString(4));
        }
        assertEquals("running", field("status"));
    }

    @Test
    void expiredLeaseRejectsOldWorkerAndRecoveryConsumesBudget() throws Exception {
        mapper.claim(1L, "lease-a", 120);
        execute("UPDATE paper_processing_job SET lease_until = TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3))");
        assertEquals(0, mapper.progress(claimed("lease-a"), "parsing", 0, 0));
        assertEquals(0, mapper.retryOrFail(1L, "lease-a", false, 30, "EXECUTION_FAILED"));
        assertEquals(1, mapper.selectExpiredForUpdate(16).size());
        assertEquals(1, mapper.retryOrFail(1L, "lease-a", true, 30, "LEASE_EXPIRED"));
        assertEquals("1", field("retry_count"));
        assertEquals("pending", field("status"));
        execute("UPDATE paper_processing_job SET next_retry_time = CURRENT_TIMESTAMP(3)");
        assertEquals(1, mapper.claim(1L, "lease-b", 120));
        assertEquals(0, mapper.progress(claimed("lease-a"), "parsing", 0, 0));
        assertEquals(0, mapper.retryOrFail(1L, "lease-b", true, 30, "LEASE_EXPIRED"));
    }

    @Test
    void retryLimitAllowsExactlyConfiguredExtraAttempts() throws Exception {
        execute("UPDATE paper_processing_job SET max_retries = 1");
        mapper.claim(1L, "lease-a", 120);
        mapper.retryOrFail(1L, "lease-a", false, 30, "DISPATCH_FAILED");
        assertEquals("pending", field("status"));
        assertEquals("1", field("retry_count"));
        assertNull(field("end_time"));
        execute("UPDATE paper_processing_job SET next_retry_time = CURRENT_TIMESTAMP(3)");
        mapper.claim(1L, "lease-b", 120);
        mapper.retryOrFail(1L, "lease-b", false, 30, "EXECUTION_FAILED");
        assertEquals("failed", field("status"));
        assertEquals("1", field("retry_count"));
        assertNotNull(field("end_time"));
        assertNull(mapper.selectDueForUpdate());
    }

    private com.ecnu.agentpapermate.entity.model.PaperProcessingJob claimed(String token) {
        var job = new com.ecnu.agentpapermate.entity.model.PaperProcessingJob();
        job.setId(1L); job.setLeaseToken(token); return job;
    }

    private String field(String column) throws Exception {
        // 列名仅来自本测试的固定字符串。
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT " + column + " FROM paper_processing_job WHERE id=1")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private void execute(String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
        if (session != null) session.clearCache();
    }
}
