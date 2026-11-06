package com.ecnu.agentpapermate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "paper.processing.scheduler.enabled=false")
@ActiveProfiles("test")
@AutoConfigureMockMvc(addFilters = false)
class AgentPaperMateApplicationTests {
    @Autowired
    private MockMvc mvc;

    @Test
    void contextLoads() {
    }

    @Test
    void apiDocumentationIncludesRunEndpointsAndControllerAdvice() throws Exception {
        //1、真正请求文档生成接口，覆盖 ControllerAdvice 与 springdoc 的二进制兼容性
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.paths['/agentRun/add'].post").exists())
                .andExpect(jsonPath("$.paths['/agentRun/cancel'].post").exists())
                .andExpect(jsonPath("$.paths['/agentRun/events'].get").exists());
    }

    @Test
    void knife4jPageAndSwaggerConfigurationRemainAvailable() throws Exception {
        //1、保留 Knife4j 页面及其读取的文档配置接口
        mvc.perform(get("/doc.html")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.configUrl").exists());
    }
}
