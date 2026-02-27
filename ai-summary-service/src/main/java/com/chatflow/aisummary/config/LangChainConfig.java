package com.chatflow.aisummary.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Slf4j
@Profile("langchain4j")
@Configuration
public class LangChainConfig {

    @Bean
    public ChatLanguageModel chatLanguageModel(
            @Value("${langchain4j.google-ai-gemini.chat-model.api-key}") String apiKey,
            @Value("${langchain4j.google-ai-gemini.chat-model.model-name}") String modelName,
            @Value("${langchain4j.google-ai-gemini.chat-model.temperature:0.3}") double temperature) {

        log.info("Initializing LangChain4J ChatLanguageModel with model: {}", modelName);

        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .build();
    }
}
