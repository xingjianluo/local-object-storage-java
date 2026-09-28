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

    /** 整包/按段成功返回时，显式给出总长度、本次区间与后续是否还有数据（整包/按段一致）。 */
    static final String H_TOTAL_SIZE = "X-Object-Total-Size";
    static final String H_RANGE_OFFSET = "X-Range-Offset";
    static final String H_RANGE_LENGTH = "X-Range-Length";
    static final String H_HAS_MORE = "X-Has-More";

    @GetMapping("/objects/{objectKey}/data")
    public ResponseEntity<StreamingResponseBody> readLatest(
            @PathVariable String objectKey,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
        if (rangeHeader != null) {
            // 先做“写法”校验（INVALID_RANGE/400），再在服务层对象锁内对照版本长度解析
            // （RANGE_NOT_SATISFIABLE/416）：版本解析、长度判断与内容加钉是同一个原子步骤
            ByteRange range = ByteRange.parseHttpHeader(rangeHeader);
            return rangeResponse(objectStorageService.readLatestRange(objectKey, range));
        }
        return fullResponse(objectStorageService.readLatest(objectKey));
    }

    @GetMapping("/objects/{objectKey}/versions/{versionId}/data")
    public ResponseEntity<StreamingResponseBody> readVersion(
            @PathVariable String objectKey,
            @PathVariable String versionId,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
        if (rangeHeader != null) {
            ByteRange range = ByteRange.parseHttpHeader(rangeHeader);
            return rangeResponse(
                    objectStorageService.readVersionRange(objectKey, versionId, range));
        }
        return fullResponse(objectStorageService.readVersion(objectKey, versionId));
    }

    /** 整包下载：流式写出，内存占用与对象大小无关；响应头同样显式给出四字段。 */
    private ResponseEntity<StreamingResponseBody> fullResponse(ObjectStorageService.RangeData data) {
        StreamingResponseBody body = out -> {
            try (InputStream in = data.stream()) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(data.length())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(H_TOTAL_SIZE, Long.toString(data.totalSize()))
                .header(H_RANGE_OFFSET, Long.toString(data.offset()))
                .header(H_RANGE_LENGTH, Long.toString(data.length()))
                .header(H_HAS_MORE, Boolean.toString(data.hasMore()))
                .body(body);
    }

    /** 范围下载：206 + Content-Range，流式写出；另显式给出总长度、本次区间与 hasMore。 */
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
                .header(H_TOTAL_SIZE, Long.toString(data.totalSize()))
                .header(H_RANGE_OFFSET, Long.toString(data.offset()))
                .header(H_RANGE_LENGTH, Long.toString(data.length()))
                .header(H_HAS_MORE, Boolean.toString(data.hasMore()))
                .body(body);
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
