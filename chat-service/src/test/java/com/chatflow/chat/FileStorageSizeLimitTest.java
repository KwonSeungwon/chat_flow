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

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * 50MB 파일 크기 제한 변경 검증 테스트.
 * 기존 20MB 제한이 50MB로 변경되었음을 명시적으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class FileStorageSizeLimitTest {

    private static final long MB = 1024 * 1024L;

    // Valid JPEG magic bytes
    private static final byte[] JPEG_MAGIC = {
        (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01
    };

    @Mock
    private FileStorageConfig config;

    @InjectMocks
    private FileStorageService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        lenient().when(config.getUploadDir()).thenReturn(tempDir.toString());
        lenient().when(config.getMaxFileSize()).thenReturn(50 * MB); // 50MB limit
        lenient().when(config.getAllowedMimeTypes()).thenReturn(
                List.of("image/jpeg", "image/png", "image/gif", "image/webp",
                        "application/pdf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/zip"));
    }

    @Test
    void store_rejectsFileExactly51MB() {
        // 51MB — must be rejected by the 50MB limit
        byte[] data = new byte[(int) (51 * MB)];
        System.arraycopy(JPEG_MAGIC, 0, data, 0, JPEG_MAGIC.length);
        MockMultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg", data);
        assertThrows(IllegalArgumentException.class, () -> service.store(file),
                "51MB 파일은 50MB 제한으로 거부되어야 합니다");
    }

    @Test
    void store_rejectsOldLimitOf21MB() {
        // 21MB — was rejected under old 20MB limit; must STILL be accepted under 50MB limit.
        // This ensures the limit was actually raised and not kept at 20MB.
        byte[] data = new byte[(int) (21 * MB)];
        System.arraycopy(JPEG_MAGIC, 0, data, 0, JPEG_MAGIC.length);
        MockMultipartFile file = new MockMultipartFile("file", "medium.jpg", "image/jpeg", data);
        // 21MB < 50MB — should NOT throw
        assertDoesNotThrow(() -> service.store(file),
                "21MB 파일은 50MB 제한 내에서 허용되어야 합니다 (이전 20MB 제한에서 업그레이드됨)");
    }

    @Test
    void store_acceptsFileExactlyAt50MB() {
        // Exactly 50MB — at the boundary, should be accepted
        byte[] data = new byte[(int) (50 * MB)];
        System.arraycopy(JPEG_MAGIC, 0, data, 0, JPEG_MAGIC.length);
        MockMultipartFile file = new MockMultipartFile("file", "exact50.jpg", "image/jpeg", data);
        assertDoesNotThrow(() -> service.store(file),
                "정확히 50MB 파일은 허용되어야 합니다");
    }

    @Test
    void fileStorageConfig_defaultMaxFileSizeIs50MB() {
        // Verify the config class itself defaults to 50MB
        FileStorageConfig realConfig = new FileStorageConfig();
        assertEquals(50 * MB, realConfig.getMaxFileSize(),
                "FileStorageConfig 기본값이 50MB(52428800 bytes)이어야 합니다");
    }
}
