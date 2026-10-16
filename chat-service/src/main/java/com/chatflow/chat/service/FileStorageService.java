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
import java.nio.charset.StandardCharsets;
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
            throw new IllegalArgumentException("파일 크기가 제한을 초과합니다 (최대 50MB).");
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
        // 감지된 content type을 사이드카에 저장 — 다운로드 시 확장자가 아닌 이 값을 사용한다
        Files.writeString(metaPath(uuid), detectedType, StandardCharsets.UTF_8);

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
            throw new IOException("파일을 찾을 수 없습니다.");
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
            throw new IOException("파일을 읽을 수 없습니다.");
        }

        String fileName = filePath.getFileName().toString();
        return new FileResource(resource, fileName, resolveContentType(uuid, fileName));
    }

    /** 업로드 시 감지된 content type. 사이드카가 없는 레거시 파일은 허용 목록에 속할 때만 이름 기반 추정값 사용. */
    public String getContentType(String uuid) throws IOException {
        Path uuidDir = resolveUuidDir(uuid);
        try (var stream = Files.list(uuidDir)) {
            String name = stream.filter(p -> !Files.isDirectory(p)).findFirst()
                    .orElseThrow(() -> new IOException("파일을 찾을 수 없습니다: " + uuid))
                    .getFileName().toString();
            return resolveContentType(uuid, name);
        }
    }

    private String resolveContentType(String uuid, String fileName) throws IOException {
        Path meta = metaPath(uuid);
        if (Files.isRegularFile(meta)) {
            String stored = Files.readString(meta, StandardCharsets.UTF_8).trim();
            if (config.getAllowedMimeTypes().contains(stored)) {
                return stored;
            }
        } else {
            String guessed = TIKA.detect(fileName);
            if (config.getAllowedMimeTypes().contains(guessed)) {
                return guessed;
            }
        }
        return "application/octet-stream";
    }

    private Path metaPath(String uuid) throws IOException {
        return resolveUuidDir(uuid).resolveSibling(uuid + ".meta").normalize();
    }

    public record FileResource(Resource resource, String fileName, String contentType) {}
}
