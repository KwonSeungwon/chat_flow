package com.chatflow.aisummary.client;

import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Profile("langchain4j")
@Component
@RequiredArgsConstructor
public class LangChainChatModelClient implements ChatModelClient {

    private final ChatLanguageModel chatLanguageModel;

    @Override
    public String generate(String prompt) {
        return chatLanguageModel.generate(prompt);
    }
}
