package com.chatflow.chat;

import com.chatflow.chat.config.JwtUtil;
import com.chatflow.chat.service.FileStorageService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "jwt.secret=test-secret-for-chat-service-testing-12345",
        "chatflow.file-storage.upload-dir=/tmp/test-uploads",
        "chatflow.file-storage.max-file-size=20971520",
        "chatflow.file-storage.allowed-mime-types=image/jpeg,image/png,application/pdf"
})
class FileControllerTest {

    private static final String TEST_SECRET = "test-secret-for-chat-service-testing-12345";
    private static final byte[] JPEG_MAGIC = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01
    };

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FileStorageService fileStorageService;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    // ── 업로드 인증 테스트 ────────────────────────────────────

    @Test
    void upload_withoutToken_returns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", JPEG_MAGIC);
        mockMvc.perform(multipart("/api/files/upload").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void upload_withInvalidToken_returns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", JPEG_MAGIC);
        mockMvc.perform(multipart("/api/files/upload").file(file)
                        .header("Authorization", "Bearer invalid.token.here"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void upload_withValidToken_returns200() throws Exception {
        when(fileStorageService.store(any()))
                .thenReturn(UUID.randomUUID().toString());

        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", JPEG_MAGIC);
        mockMvc.perform(multipart("/api/files/upload").file(file)
                        .header("Authorization", "Bearer " + generateToken("user1", "testuser")))
                .andExpect(status().isOk());
    }

    @Test
    void upload_withExpiredToken_returns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", JPEG_MAGIC);
        mockMvc.perform(multipart("/api/files/upload").file(file)
                        .header("Authorization", "Bearer " + generateExpiredToken()))
                .andExpect(status().isUnauthorized());
    }

    // ── 다운로드 (permit all) ──────────────────────────────────

    @Test
    void download_withoutToken_returns404ForNonexistentUuid() throws Exception {
        String uuid = UUID.randomUUID().toString();
        when(fileStorageService.loadAsResource(eq(uuid)))
                .thenThrow(new IOException("not found"));
        mockMvc.perform(get("/api/files/" + uuid))
                .andExpect(status().isNotFound());
    }

    @Test
    void download_withoutToken_returns200ForExistingFile() throws Exception {
        String uuid = UUID.randomUUID().toString();
        org.springframework.core.io.ByteArrayResource resource =
                new org.springframework.core.io.ByteArrayResource(JPEG_MAGIC);
        FileStorageService.FileResource fr =
                new FileStorageService.FileResource(resource, "photo.jpg", "image/jpeg");
        when(fileStorageService.loadAsResource(eq(uuid))).thenReturn(fr);

        mockMvc.perform(get("/api/files/" + uuid))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void download_responseHasNoWildcardCorsHeader() throws Exception {
        String uuid = UUID.randomUUID().toString();
        org.springframework.core.io.ByteArrayResource resource =
                new org.springframework.core.io.ByteArrayResource(JPEG_MAGIC);
        FileStorageService.FileResource fr =
                new FileStorageService.FileResource(resource, "photo.jpg", "image/jpeg");
        when(fileStorageService.loadAsResource(eq(uuid))).thenReturn(fr);

        mockMvc.perform(get("/api/files/" + uuid))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    // ── 헬퍼 ──────────────────────────────────────────────────

    private String generateToken(String userId, String username) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(key)
                .compact();
    }

    private String generateExpiredToken() {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject("user1")
                .claim("username", "testuser")
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000))
                .expiration(new Date(System.currentTimeMillis() - 3_600_000))
                .signWith(key)
                .compact();
    }
}
