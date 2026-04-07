package com.chatflow.chat.controller;

import com.chatflow.chat.service.FileStorageService;
import com.chatflow.chat.service.FileStorageService.FileResource;
import com.chatflow.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            @RequestParam("file") MultipartFile file) {
        try {
            String uuid = fileStorageService.store(file);
            String originalFilename = file.getOriginalFilename() != null
                    ? file.getOriginalFilename() : "file";
            String contentType = file.getContentType() != null
                    ? file.getContentType() : "application/octet-stream";

            Map<String, Object> data = Map.of(
                    "fileUrl", "/api/files/" + uuid,
                    "fileName", originalFilename,
                    "fileContentType", contentType
            );
            return ResponseEntity.ok(ApiResponse.ok(data));
        } catch (IllegalArgumentException e) {
            log.warn("파일 업로드 검증 실패: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(e.getMessage()));
        } catch (IOException e) {
            log.error("파일 저장 실패", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("파일 저장에 실패했습니다."));
        }
    }

    @GetMapping("/{uuid}")
    public ResponseEntity<Resource> download(@PathVariable String uuid) {
        try {
            FileResource fr = fileStorageService.loadAsResource(uuid);
            boolean isImage = fr.contentType().startsWith("image/");
            String disposition = isImage
                    ? "inline; filename=\"" + encodeFilename(fr.fileName()) + "\""
                    : "attachment; filename=\"" + encodeFilename(fr.fileName()) + "\"";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(fr.contentType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .header("X-Content-Type-Options", "nosniff")
                    .body(fr.resource());
        } catch (IOException e) {
            log.warn("파일 다운로드 실패: uuid={}", uuid);
            return ResponseEntity.notFound().build();
        }
    }

    private String encodeFilename(String filename) {
        return URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
