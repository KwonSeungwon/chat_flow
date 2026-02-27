package com.chatflow.aisummary.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Profile("spring-ai")
@Component
public class SpringAiChatModelClient implements ChatModelClient {

    private final ChatClient chatClient;

    public SpringAiChatModelClient(ChatClient.Builder builder) {
        this.chatClient = builder.build();
        log.info("Spring AI ChatModelClient initialized (Google Gemini)");
    }

    @Override
    public String generate(String prompt) {
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }
}
