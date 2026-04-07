package com.chatflow.chat.service;

import com.chatflow.chat.config.FileStorageConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private static final org.apache.tika.Tika TIKA = new org.apache.tika.Tika();

    private final FileStorageConfig config;

    /**
     * 파일을 저장하고 UUID를 반환합니다.
     * 저장 경로: {uploadDir}/{uuid}/{originalFilename}
     */
    public String store(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("빈 파일은 업로드할 수 없습니다.");
        }
        if (file.getSize() > config.getMaxFileSize()) {
            throw new IllegalArgumentException("파일 크기가 제한을 초과합니다 (최대 20MB).");
        }

        String detectedType;
        try {
            byte[] header = new byte[Math.min(512, (int) file.getSize())];
            int read;
            try (InputStream is = file.getInputStream()) {
                read = is.read(header, 0, header.length);
            }
            detectedType = TIKA.detect(Arrays.copyOf(header, read > 0 ? read : 0),
                    file.getOriginalFilename());
        } catch (IOException e) {
            throw new IllegalArgumentException("파일 형식을 확인할 수 없습니다.");
        }
        if (!config.getAllowedMimeTypes().contains(detectedType)) {
            throw new IllegalArgumentException("허용되지 않는 파일 형식입니다: " + detectedType);
        }

        String uuid = UUID.randomUUID().toString();
        Path uuidDir = resolveUuidDir(uuid);
        Files.createDirectories(uuidDir);

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            originalFilename = "file";
        }
        // Sanitize: keep only the filename portion, no path traversal
        originalFilename = Paths.get(originalFilename).getFileName().toString();
        if (originalFilename.length() > 255) {
            originalFilename = originalFilename.substring(0, 255);
        }

        Path dest = uuidDir.resolve(originalFilename);
        Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);

        log.info("파일 저장 완료: uuid={}, name={}", uuid, originalFilename);
        return uuid;
    }

    private static final java.util.regex.Pattern UUID_PATTERN =
            java.util.regex.Pattern.compile(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private Path resolveUuidDir(String uuid) throws IOException {
        if (uuid == null || !UUID_PATTERN.matcher(uuid).matches()) {
            throw new IllegalArgumentException("잘못된 파일 식별자입니다.");
        }
        Path base = Paths.get(config.getUploadDir()).toAbsolutePath().normalize();
        Path uuidDir = base.resolve(uuid).normalize();
        if (!uuidDir.startsWith(base)) {
            throw new IOException("잘못된 파일 경로입니다.");
        }
        return uuidDir;
    }

    /**
     * UUID로 파일을 찾아 Resource를 반환합니다.
     */
    public FileResource loadAsResource(String uuid) throws MalformedURLException, IOException {
        Path uuidDir = resolveUuidDir(uuid);
        if (!Files.exists(uuidDir) || !Files.isDirectory(uuidDir)) {
            throw new IOException("파일을 찾을 수 없습니다: " + uuid);
        }

        Path filePath;
        try (var stream = Files.list(uuidDir)) {
            filePath = stream
                    .filter(p -> !Files.isDirectory(p))
                    .findFirst()
                    .orElseThrow(() -> new IOException("파일을 찾을 수 없습니다: " + uuid));
        }

        Resource resource = new UrlResource(filePath.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            throw new IOException("파일을 읽을 수 없습니다: " + uuid);
        }

        String contentType = Files.probeContentType(filePath);
        if (contentType == null) contentType = "application/octet-stream";

        return new FileResource(resource, filePath.getFileName().toString(), contentType);
    }

    public record FileResource(Resource resource, String fileName, String contentType) {}
}
