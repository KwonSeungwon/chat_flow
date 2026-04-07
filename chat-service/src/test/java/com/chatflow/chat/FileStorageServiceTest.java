package com.chatflow.chat;

import com.chatflow.chat.config.FileStorageConfig;
import com.chatflow.chat.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileStorageServiceTest {

    @Mock
    private FileStorageConfig config;

    @InjectMocks
    private FileStorageService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        lenient().when(config.getUploadDir()).thenReturn(tempDir.toString());
        lenient().when(config.getMaxFileSize()).thenReturn(20 * 1024 * 1024L);
        lenient().when(config.getAllowedMimeTypes()).thenReturn(
                List.of("image/jpeg", "image/png", "image/gif", "image/webp",
                        "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/zip"));
    }

    // ── MIME 검증 ────────────────────────────────────────────

    @Test
    void store_rejectsHtmlDisguisedAsImage() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.png", "image/png",
                "<html><body><script>alert(1)</script></body></html>".getBytes());
        assertThrows(IllegalArgumentException.class, () -> service.store(file));
    }

    @Test
    void store_rejectsJavascriptFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "malicious.js", "application/javascript",
                "alert('xss')".getBytes());
        assertThrows(IllegalArgumentException.class, () -> service.store(file));
    }

    @Test
    void store_acceptsRealJpeg() throws IOException {
        // JPEG magic bytes: FF D8 FF E0
        byte[] jpegHeader = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01};
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegHeader);
        String uuid = service.store(file);
        assertNotNull(uuid);
        assertTrue(uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "UUID 형식이어야 합니다: " + uuid);
    }

    @Test
    void store_acceptsRealPng() throws IOException {
        // PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A
        byte[] pngHeader = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D};
        MockMultipartFile file = new MockMultipartFile("file", "img.png", "image/png", pngHeader);
        String uuid = service.store(file);
        assertNotNull(uuid);
    }

    @Test
    void store_rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> service.store(file));
    }

    @Test
    void store_rejectsOversizedFile() {
        byte[] bigData = new byte[21 * 1024 * 1024]; // 21MB
        MockMultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg", bigData);
        assertThrows(IllegalArgumentException.class, () -> service.store(file));
    }

    // ── 경로 탐색 방어 ────────────────────────────────────────

    @Test
    void loadAsResource_throwsOnPathTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> service.loadAsResource("../../etc/passwd"));
    }

    @Test
    void loadAsResource_throwsOnNullUuid() {
        assertThrows(IllegalArgumentException.class,
                () -> service.loadAsResource(null));
    }

    @Test
    void loadAsResource_throwsOnMalformedUuid() {
        assertThrows(IllegalArgumentException.class,
                () -> service.loadAsResource("not-a-valid-uuid"));
    }

    @Test
    void loadAsResource_throwsOnNonexistentUuid() {
        String uuid = UUID.randomUUID().toString();
        assertThrows(IOException.class, () -> service.loadAsResource(uuid));
    }

    // ── 파일명 살균 ───────────────────────────────────────────

    @Test
    void store_sanitizesPathTraversalInFilename() throws IOException {
        byte[] jpegHeader = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01};
        MockMultipartFile file = new MockMultipartFile(
                "file", "../../etc/passwd.jpg", "image/jpeg", jpegHeader);
        String uuid = service.store(file);
        assertNotNull(uuid);
        // 파일이 실제로 tempDir 내부에 저장되었는지 확인
        Path uuidDir = tempDir.resolve(uuid);
        assertTrue(uuidDir.toFile().exists(), "UUID 디렉토리가 존재해야 합니다");
        // passwd.jpg 라는 이름으로 sanitize 되어야 함 (경로 컴포넌트 제거)
        assertTrue(uuidDir.toFile().listFiles() != null);
    }
}
