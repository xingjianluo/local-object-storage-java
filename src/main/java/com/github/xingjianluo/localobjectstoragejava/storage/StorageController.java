package com.github.xingjianluo.localobjectstoragejava.storage;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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
    public ResponseEntity<StreamingResponseBody> readLatest(
            @PathVariable String objectKey,
            @RequestHeader(value = "Range", required = false) String range) {
        if (range != null) {
            return rangeResponse(range, (start, end) ->
                    objectStorageService.readRangeLatest(objectKey, start, end));
        }
        ObjectStorageService.ObjectData data = objectStorageService.readLatest(objectKey);
        return fullResponse(data);
    }

    @GetMapping("/objects/{objectKey}/versions/{versionId}/data")
    public ResponseEntity<StreamingResponseBody> readVersion(
            @PathVariable String objectKey,
            @PathVariable String versionId,
            @RequestHeader(value = "Range", required = false) String range) {
        if (range != null) {
            return rangeResponse(range, (start, end) ->
                    objectStorageService.readRangeVersion(objectKey, versionId, start, end));
        }
        ObjectStorageService.ObjectData data = objectStorageService.readVersion(objectKey, versionId);
        return fullResponse(data);
    }

    @FunctionalInterface
    private interface RangeReader {
        ObjectStorageService.RangeData read(long start, long end);
    }

    /** 整包流式响应：不在内存中缓冲对象内容。 */
    private static ResponseEntity<StreamingResponseBody> fullResponse(
            ObjectStorageService.ObjectData data) {
        StreamingResponseBody body = out -> {
            try (InputStream in = data.stream()) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .contentLength(data.version().size())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header("X-Object-Total-Size", Long.toString(data.version().size()))
                .body(body);
    }

    /**
     * 范围流式响应：206 + Content-Range，并通过 X-* 头明确给出
     * 总长度、实际区间与是否还有后续数据。
     */
    private static ResponseEntity<StreamingResponseBody> rangeResponse(String rangeHeader,
                                                                       RangeReader reader) {
        long[] bounds = parseRangeHeader(rangeHeader);
        ObjectStorageService.RangeData data = reader.read(bounds[0], bounds[1]);
        StreamingResponseBody body = out -> {
            try (InputStream in = data.stream()) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentLength(data.rangeLength())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE,
                        "bytes " + data.rangeStart() + "-" + data.rangeEnd() + "/" + data.totalSize())
                .header("X-Object-Total-Size", Long.toString(data.totalSize()))
                .header("X-Range-Start", Long.toString(data.rangeStart()))
                .header("X-Range-End", Long.toString(data.rangeEnd()))
                .header("X-Has-More", Boolean.toString(data.hasMore()))
                .body(body);
    }

    /** 解析 {@code Range: bytes=start-end} / {@code bytes=start-}，其余形式明确拒绝。 */
    private static long[] parseRangeHeader(String header) {
        StorageException invalid = new StorageException(StorageException.ErrorCode.INVALID_RANGE,
                "无法解析的 Range 头: " + header + "（仅支持 bytes=start-end 或 bytes=start-）");
        if (!header.startsWith("bytes=")) {
            throw invalid;
        }
        String spec = header.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash <= 0 || dash != spec.lastIndexOf('-')) {
            throw invalid;
        }
        try {
            long start = Long.parseLong(spec.substring(0, dash).trim());
            String endPart = spec.substring(dash + 1).trim();
            long end = endPart.isEmpty() ? Long.MAX_VALUE : Long.parseLong(endPart);
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            throw invalid;
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
            case RANGE_NOT_SATISFIABLE -> HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(Map.of("errorCode", e.getCode().name(), "message", e.getMessage()));
    }
}
