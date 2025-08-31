package com.chatflow.aisummary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
@ComponentScan(basePackages = {"com.chatflow.aisummary", "com.chatflow.common"})
public class AiSummaryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiSummaryServiceApplication.class, args);
    }
}