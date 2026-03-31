package com.chatflow.chat.config;

import com.chatflow.chat.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WarmupRunner implements ApplicationRunner {

    private final ChatRoomRepository chatRoomRepository;

    @Override
    public void run(ApplicationArguments args) {
        long start = System.currentTimeMillis();
        long roomCount = chatRoomRepository.count();
        long elapsed = System.currentTimeMillis() - start;
        log.info("Warmup complete: {} rooms loaded, HikariCP + Hibernate initialized in {}ms", roomCount, elapsed);
    }
}
