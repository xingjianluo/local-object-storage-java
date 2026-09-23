package com.github.xingjianluo.localobjectstoragejava.storage;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 对象存储 REST 接口。
 */
@RestController
@RequestMapping("/api/storage")
public class StorageController {

    private final UploadSessionManager sessionManager;
    private final ObjectStorageService objectStorageService;

    public StorageController(UploadSessionManager sessionManager,
                             ObjectStorageService objectStorageService) {
        this.sessionManager = sessionManager;
        this.objectStorageService = objectStorageService;
    }

    public record CreateSessionRequest(String objectKey, List<UploadSessionManager.PartSpec> parts) {}

    public record CreateSessionResponse(String sessionId) {}

    public record CompleteResponse(String versionId) {}

    public record RollbackResponse(String versionId) {}

    @PostMapping("/sessions")
    public CreateSessionResponse createSession(@RequestBody CreateSessionRequest request) {
        String sessionId = sessionManager.createSession(request.objectKey(), request.parts());
        return new CreateSessionResponse(sessionId);
    }

    @GetMapping("/sessions/{sessionId}")
    public UploadSessionManager.SessionInfo sessionInfo(@PathVariable String sessionId) {
        return sessionManager.sessionInfo(sessionId);
    }

    @PutMapping("/sessions/{sessionId}/parts/{partNumber}")
    public void uploadPart(@PathVariable String sessionId,
                           @PathVariable int partNumber,
                           @RequestParam long size,
                           HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream()) {
            sessionManager.uploadPart(sessionId, partNumber, in, size);
        }
    }

    @PostMapping("/sessions/{sessionId}/complete")
    public CompleteResponse complete(@PathVariable String sessionId) {
        return new CompleteResponse(sessionManager.complete(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/abort")
    public void abort(@PathVariable String sessionId) {
        sessionManager.abort(sessionId);
    }

    @GetMapping("/objects/{objectKey}/versions")
    public List<ObjectStorageService.VersionInfo> versions(@PathVariable String objectKey) {
        return objectStorageService.listVersions(objectKey);
    }

    @GetMapping("/objects/{objectKey}/data")
    public ResponseEntity<byte[]> readLatest(@PathVariable String objectKey) throws IOException {
        ObjectStorageService.ObjectData data = objectStorageService.readLatest(objectKey);
        try (InputStream in = data.stream()) {
            return ResponseEntity.ok(in.readAllBytes());
        }
    }

    @GetMapping("/objects/{objectKey}/versions/{versionId}/data")
    public ResponseEntity<byte[]> readVersion(@PathVariable String objectKey,
                                              @PathVariable String versionId) throws IOException {
        ObjectStorageService.ObjectData data = objectStorageService.readVersion(objectKey, versionId);
        try (InputStream in = data.stream()) {
            return ResponseEntity.ok(in.readAllBytes());
        }
    }

    @DeleteMapping("/objects/{objectKey}/versions/{versionId}")
    public void deleteVersion(@PathVariable String objectKey, @PathVariable String versionId) {
        objectStorageService.deleteVersion(objectKey, versionId);
    }

    @DeleteMapping("/objects/{objectKey}")
    public void deleteObject(@PathVariable String objectKey) {
        objectStorageService.deleteObject(objectKey);
    }

    @PostMapping("/objects/{objectKey}/rollback/{versionId}")
    public RollbackResponse rollback(@PathVariable String objectKey, @PathVariable String versionId) {
        return new RollbackResponse(objectStorageService.rollback(objectKey, versionId));
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<Map<String, String>> handleStorageException(StorageException e) {
        HttpStatus status = switch (e.getCode()) {
            case SESSION_NOT_FOUND, OBJECT_NOT_FOUND, VERSION_NOT_FOUND, CONTENT_MISSING ->
                    HttpStatus.NOT_FOUND;
            case SESSION_EXPIRED, SESSION_ALREADY_COMPLETED, SESSION_ALREADY_ABORTED ->
                    HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(Map.of("errorCode", e.getCode().name(), "message", e.getMessage()));
    }
}
