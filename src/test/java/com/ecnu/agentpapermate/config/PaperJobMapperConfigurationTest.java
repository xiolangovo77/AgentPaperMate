package com.ecnu.agentpapermate.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PaperJobMapperConfigurationTest {
    @Test
    void mapperXmlAndBaseMapperLoadTogetherWithoutDatabase() throws Exception {
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(mock(DataSource.class));
        bean.setConfiguration(new MybatisConfiguration());
        bean.setMapperLocations(new ClassPathResource("mapper/PaperProcessingJobMapper.xml"));
        var configuration = bean.getObject().getConfiguration();
        String prefix = "com.ecnu.agentpapermate.mapper.PaperProcessingJobMapper.";
        for (String method : new String[]{"selectById", "selectDueForUpdate", "claim", "selectExpiredForUpdate", "renew", "progress", "complete", "updateParsedMetadata"}) {
            assertTrue(configuration.hasStatement(prefix + method), method);
        }
        var statement = configuration.getMappedStatement(prefix + "retryOrFail");
        assertNotNull(statement.getBoundSql(Map.of("expired", true)));
        assertNotNull(statement.getBoundSql(Map.of("expired", false)));
    }
}
