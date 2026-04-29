package com.cms.controller;

import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/cms/media")
public class MediaController {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final int MAX_WIDTH = 1920;
    private static final int MAX_HEIGHT = 1080;
    private static final double OUTPUT_QUALITY = 0.8;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".png", ".jpg", ".jpeg", ".webp");
    private static final String CDN_CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final Path uploadDir;

    public MediaController(@Value("${app.upload-dir}") String uploadDir) {
        this.uploadDir = Paths.get(uploadDir).toAbsolutePath().normalize();
    }
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> upload(@RequestPart("file") MultipartFile file) throws IOException {

        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File exceeds 10 MB limit");
        }

        String ext = extractExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported format. Allowed: " + ALLOWED_EXTENSIONS);
        }

        Files.createDirectories(uploadDir);

        // Always store as .webp — best compression + quality ratio
        String storedName = UUID.randomUUID() + ".webp";
        Path target = uploadDir.resolve(storedName);

        // Compress + resize + convert to webp
        try (InputStream in = file.getInputStream()) {
            Thumbnails.of(in)
                    .size(MAX_WIDTH, MAX_HEIGHT)       // scale down if larger, keep aspect ratio
                    .keepAspectRatio(true)
                    .outputQuality(OUTPUT_QUALITY)     // 80% → ~60-70% smaller than original
                    .outputFormat("webp")
                    .toFile(target.toFile());
        }

        return Map.of(
                "url", "/uploads/" + storedName,
                "format", "webp"
        );

    }
    private String extractExtension(String filename) {
        String name = filename != null ? filename : "file";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase() : "";
    }

    @GetMapping("/uploads/{filename}")
    public ResponseEntity<Resource> serveFile(@PathVariable String filename) throws IOException {

        Path filePath = uploadDir.resolve(filename).normalize();

        // Security: prevent path traversal (e.g. ../../etc/passwd)
        if (!filePath.startsWith(uploadDir)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        Resource resource = new UrlResource(filePath.toUri());
        if (!resource.exists() || !resource.isReadable()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }

        String contentType = Files.probeContentType(filePath);
        if (contentType == null) contentType = "application/octet-stream";

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CDN_CACHE_CONTROL)  // CDN caches for 1 year
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header("X-Content-Type-Options", "nosniff")            // security header
                .body(resource);
    }

}
