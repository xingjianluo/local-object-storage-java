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
                .contentLength(data.length())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .headers(headers -> segmentHeaders(headers, data.version().versionId(),
                        data.totalSize(), data.offset(), data.length(), data.hasMore()))
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
                .headers(headers -> segmentHeaders(headers, data.version().versionId(),
                        data.totalSize(), data.offset(), data.length(), data.hasMore()))
                .body(body);
    }

    /**
     * 整包（200）与按段（206）共用的分段元信息响应头，客户端无需用
     * Content-Range 自行推算：对象总长度、本次区间起止、是否还有后续数据一目了然。
     */
    private static void segmentHeaders(HttpHeaders headers, String versionId,
                                       long totalSize, long offset, long length, boolean hasMore) {
        headers.add("X-Object-Version-Id", versionId);
        headers.add("X-Object-Total-Size", Long.toString(totalSize));
        headers.add("X-Object-Offset", Long.toString(offset));
        headers.add("X-Object-Length", Long.toString(length));
        headers.add("X-Object-Has-More", Boolean.toString(hasMore));
    }

    /**
     * 解析 {@code Range: bytes=start-end | bytes=start- | bytes=-suffix}，返回 {offset, length}。
     *
     * <p>本方法只做语法/写法校验，以下情况抛 {@code INVALID_RANGE}（HTTP 400）：
     * 无法识别的格式、多区间、起止颠倒（end &lt; start）、负数、零长度后缀；
     * 而“写法合法但起点达到/超过对象长度”不在此拒绝（如 {@code bytes=100-}），
     * 由服务层统一抛 {@code RANGE_NOT_SATISFIABLE}（HTTP 416），两类失败稳定可区分。</p>
     */
    public static long[] parseRangeHeader(String header, long totalSize) {
        String h = header.trim();
        if (!h.regionMatches(true, 0, "bytes=", 0, "bytes=".length())) {
            throw new StorageException(StorageException.ErrorCode.INVALID_RANGE,
                    "无法识别的 Range 头: " + header);
        }
        String spec = h.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0 || spec.indexOf(',', 0) >= 0) {
            throw new StorageException(StorageException.ErrorCode.INVALID_RANGE,
                    "无法识别的 Range 头（仅支持单段范围）: " + header);
        }
        String startPart = spec.substring(0, dash).trim();
        String endPart = spec.substring(dash + 1).trim();
        try {
            if (startPart.isEmpty()) {
                // 后缀范围：最后 N 字节；N<=0 属于写法错误
                long suffix = Long.parseLong(endPart);
                if (suffix <= 0) {
                    throw new NumberFormatException("suffix <= 0");
                }
                long offset = Math.max(0, totalSize - suffix);
                return new long[]{offset, totalSize - offset};
            }
            long start = Long.parseLong(startPart);
            if (start < 0) {
                throw new NumberFormatException("negative start");
            }
            if (endPart.isEmpty()) {
                // bytes=start-：长度交给服务层按对象长度判定（start>=total 时为 416）
                return new long[]{start, totalSize - start};
            }
            long end = Long.parseLong(endPart);
            if (end < start) {
                // 起止颠倒：范围写法本身错误，与对象长度无关
                throw new NumberFormatException("end < start");
            }
            return new long[]{start, end - start + 1};
        } catch (NumberFormatException e) {
            throw new StorageException(StorageException.ErrorCode.INVALID_RANGE,
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
            case INVALID_RANGE -> HttpStatus.BAD_REQUEST;
            case RANGE_NOT_SATISFIABLE -> HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(Map.of("errorCode", e.getCode().name(), "message", e.getMessage()));
    }
}
