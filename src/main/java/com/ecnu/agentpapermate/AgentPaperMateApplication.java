package com.ecnu.agentpapermate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentPaperMateApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(AgentPaperMateApplication.class, args);
        if (context.getEnvironment().getProperty("bench.retrieval.enabled", Boolean.class, false)) {
            System.exit(SpringApplication.exit(context));
        }
    }

}
