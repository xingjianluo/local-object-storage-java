package com.github.xingjianluo.localobjectstoragejava.storage;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
        if (rangeHeader != null) {
            ObjectStorageService.VersionInfo info = objectStorageService.statLatest(objectKey);
            long[] range = parseRangeHeader(rangeHeader, info.size());
            return rangeResponse(objectStorageService.readLatestRange(objectKey, range[0], range[1]));
        }
        return fullResponse(objectStorageService.readLatest(objectKey));
    }

    @GetMapping("/objects/{objectKey}/versions/{versionId}/data")
    public ResponseEntity<StreamingResponseBody> readVersion(
            @PathVariable String objectKey,
            @PathVariable String versionId,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
        if (rangeHeader != null) {
            ObjectStorageService.VersionInfo info = objectStorageService.statVersion(objectKey, versionId);
            long[] range = parseRangeHeader(rangeHeader, info.size());
            return rangeResponse(objectStorageService.readVersionRange(objectKey, versionId, range[0], range[1]));
        }
        return fullResponse(objectStorageService.readVersion(objectKey, versionId));
    }

    /** 整包下载：流式写出，内存占用与对象大小无关。 */
    private ResponseEntity<StreamingResponseBody> fullResponse(ObjectStorageService.ObjectData data) {
        StreamingResponseBody body = out -> {
            try (InputStream in = data.stream()) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(data.version().size())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(body);
    }

    /** 范围下载：206 + Content-Range，流式写出。 */
    private ResponseEntity<StreamingResponseBody> rangeResponse(ObjectStorageService.RangeData data) {
        StreamingResponseBody body = out -> {
            try (InputStream in = data.stream()) {
                in.transferTo(out);
            }
        };
        long end = data.offset() + data.length() - 1;
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(data.length())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE,
                        "bytes " + data.offset() + "-" + end + "/" + data.totalSize())
                .body(body);
    }

    /**
     * 解析 {@code Range: bytes=start-end | bytes=start- | bytes=-suffix}，
     * 返回 {offset, length}；格式非法时抛 RANGE_NOT_SATISFIABLE（映射为 416）。
     */
    public static long[] parseRangeHeader(String header, long totalSize) {
        String h = header.trim();
        if (!h.regionMatches(true, 0, "bytes=", 0, "bytes=".length())) {
            throw new StorageException(StorageException.ErrorCode.RANGE_NOT_SATISFIABLE,
                    "无法识别的 Range 头: " + header);
        }
        String spec = h.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0 || spec.indexOf(',', 0) >= 0) {
            throw new StorageException(StorageException.ErrorCode.RANGE_NOT_SATISFIABLE,
                    "无法识别的 Range 头: " + header);
        }
        String startPart = spec.substring(0, dash).trim();
        String endPart = spec.substring(dash + 1).trim();
        try {
            if (startPart.isEmpty()) {
                // 后缀范围：最后 N 字节
                long suffix = Long.parseLong(endPart);
                if (suffix <= 0) {
                    throw new NumberFormatException("suffix <= 0");
                }
                long offset = Math.max(0, totalSize - suffix);
                return new long[]{offset, totalSize - offset};
            }
            long start = Long.parseLong(startPart);
            long length = endPart.isEmpty()
                    ? totalSize - start
                    : Long.parseLong(endPart) - start + 1;
            if (start < 0 || (!endPart.isEmpty() && length <= 0)) {
                throw new NumberFormatException("invalid range bounds");
            }
            return new long[]{start, length};
        } catch (NumberFormatException e) {
            throw new StorageException(StorageException.ErrorCode.RANGE_NOT_SATISFIABLE,
                    "无法识别的 Range 头: " + header);
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
