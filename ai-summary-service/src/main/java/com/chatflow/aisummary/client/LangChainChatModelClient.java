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
        try {
            return chatLanguageModel.generate(prompt);
        } catch (Exception e) {
            log.error("Gemini API 호출 실패: {}", e.getMessage());
            String msg = (e.getMessage() != null &&
                         (e.getMessage().contains("quota") ||
                          e.getMessage().contains("RESOURCE_EXHAUSTED") ||
                          e.getMessage().contains("429") ||
                          e.getMessage().contains("403")))
                ? "일일한도를 초과했습니다. 잠시 후 다시 시도해 주세요."
                : "AI 서비스 호출에 실패했습니다. 잠시 후 다시 시도해 주세요.";
            throw new IllegalStateException(msg, e);
        }
    }
}
