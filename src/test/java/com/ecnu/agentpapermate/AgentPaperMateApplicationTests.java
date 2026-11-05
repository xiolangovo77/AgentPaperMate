package com.ecnu.agentpapermate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "paper.processing.scheduler.enabled=false")
class AgentPaperMateApplicationTests {

    @Test
    void contextLoads() {
    }

}
