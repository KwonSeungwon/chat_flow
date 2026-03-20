package com.chatflow.aisummary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableKafka
@EnableScheduling
@ComponentScan(basePackages = {"com.chatflow.aisummary", "com.chatflow.common"})
public class AiSummaryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiSummaryServiceApplication.class, args);
    }
}