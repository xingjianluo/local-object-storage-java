package com.github.xingjianluo.localobjectstoragejava.web;

import com.github.xingjianluo.localobjectstoragejava.model.ObjectMeta;
import com.github.xingjianluo.localobjectstoragejava.model.ObjectVersion;
import com.github.xingjianluo.localobjectstoragejava.model.PartInfo;
import com.github.xingjianluo.localobjectstoragejava.model.UploadSession;
import com.github.xingjianluo.localobjectstoragejava.service.MultipartUploadService;
import com.github.xingjianluo.localobjectstoragejava.service.ObjectService;
import com.github.xingjianluo.localobjectstoragejava.service.StorageAdminService;
import com.github.xingjianluo.localobjectstoragejava.web.dto.Dtos;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.List;

/**
 * 本地对象存储 REST 入口。
 *
 * <pre>
 * 创建会话  POST   /api/uploads
 * 上传分片  PUT    /api/uploads/{sid}/parts/{n}            header: X-Part-Size, X-Part-Checksum(SHA-256 hex)
 * 完成合并  POST   /api/uploads/{sid}/complete             body: {"checksum":"整体SHA-256"}
 * 中止会话  DELETE /api/uploads/{sid}
 * 查询会话  GET    /api/uploads/{sid}
 * 读当前版  GET    /api/objects/current?key=
 * 读指定版  GET    /api/objects/versions/{vid}?key=
 * 版本列表  GET    /api/objects?key=
 * 删除版本  DELETE /api/objects/versions/{vid}?key=
 * 版本回滚  POST   /api/objects/rollback?key=             body: {"versionId":"..."}
 * 手动回收  POST   /api/admin/reap
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class StorageController {

    private final MultipartUploadService multipartUploadService;
    private final ObjectService objectService;
    private final StorageAdminService adminService;

    public StorageController(MultipartUploadService multipartUploadService,
                             ObjectService objectService,
                             StorageAdminService adminService) {
        this.multipartUploadService = multipartUploadService;
        this.objectService = objectService;
        this.adminService = adminService;
    }

    // ---------------- 分片上传 ----------------

    @PostMapping("/uploads")
    public Dtos.SessionResponse createSession(@RequestBody Dtos.CreateSessionRequest request) {
        UploadSession session = multipartUploadService.createSession(
                request.objectKey(), request.totalParts(), request.partSize());
        return toSessionResponse(session);
    }

    @PutMapping("/uploads/{sid}/parts/{number}")
    public Dtos.SessionResponse uploadPart(@PathVariable String sid,
                                           @PathVariable int number,
                                           @RequestHeader("X-Part-Size") long partSize,
                                           @RequestHeader(value = "X-Part-Checksum", required = false)
                                           String checksum,
                                           HttpServletRequest request) throws java.io.IOException {
        // 直接使用原始请求体流，不经过 HttpMessageConverter，支持任意 Content-Type。
        UploadSession session = multipartUploadService.uploadPart(sid, number, partSize, checksum,
                request.getInputStream());
        return toSessionResponse(session);
    }

    @PostMapping("/uploads/{sid}/complete")
    public Dtos.SessionResponse complete(@PathVariable String sid,
                                         @RequestBody(required = false) Dtos.CompleteRequest request) {
        String checksum = request == null ? null : request.checksum();
        UploadSession session = multipartUploadService.completeSession(sid, checksum);
        return toSessionResponse(session);
    }

    @DeleteMapping("/uploads/{sid}")
    public ResponseEntity<Void> abort(@PathVariable String sid) {
        multipartUploadService.abortSession(sid);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/uploads/{sid}")
    public Dtos.SessionResponse session(@PathVariable String sid) {
        return toSessionResponse(multipartUploadService.getSession(sid));
    }

    // ---------------- 对象与版本 ----------------

    @GetMapping("/objects/current")
    public ResponseEntity<org.springframework.core.io.Resource> readCurrent(@RequestParam String key) {
        return objectResponse(objectService.readCurrent(key));
    }

    @GetMapping("/objects/versions/{vid}")
    public ResponseEntity<org.springframework.core.io.Resource> readVersion(@RequestParam String key,
                                                                            @PathVariable String vid) {
        return objectResponse(objectService.readVersion(key, vid));
    }

    @GetMapping("/objects")
    public Dtos.ObjectMetaResponse versions(@RequestParam String key) {
        return toMetaResponse(objectService.listVersions(key));
    }

    @DeleteMapping("/objects/versions/{vid}")
    public ResponseEntity<Void> deleteVersion(@RequestParam String key, @PathVariable String vid) {
        objectService.deleteVersion(key, vid);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/objects/rollback")
    public Dtos.VersionResponse rollback(@RequestParam String key,
                                         @RequestBody Dtos.RollbackRequest request) {
        return toVersionResponse(objectService.rollback(key, request.versionId()));
    }

    // ---------------- 管理 ----------------

    @PostMapping("/admin/reap")
    public Dtos.ReapResponse reap() {
        int[] r = adminService.reapNow();
        return new Dtos.ReapResponse(r[0], r[1]);
    }

    // ---------------- 转换 ----------------

    private ResponseEntity<org.springframework.core.io.Resource> objectResponse(ReadableByteChannel channel) {
        org.springframework.core.io.InputStreamResource body =
                new org.springframework.core.io.InputStreamResource(Channels.newInputStream(channel));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .body(body);
    }

    private static Dtos.SessionResponse toSessionResponse(UploadSession s) {
        List<Dtos.PartInfoResponse> parts = s.getParts().entrySet().stream()
                .map(e -> {
                    PartInfo p = e.getValue();
                    return new Dtos.PartInfoResponse(p.getNumber(), p.getSize(), p.getChecksum(),
                            p.getUploadedAtMillis());
                })
                .toList();
        return new Dtos.SessionResponse(s.getSessionId(), s.getObjectKey(), s.getTotalParts(),
                s.getPartSize(), s.getState().name(), s.getCreatedAtMillis(), s.getExpiresAtMillis(),
                parts, s.getCompletedVersionId(), s.getContentId());
    }

    private static Dtos.VersionResponse toVersionResponse(ObjectVersion v) {
        return new Dtos.VersionResponse(v.getVersionId(), v.getObjectKey(), v.getContentId(),
                v.getSize(), v.getChecksum(), v.getCreatedAtMillis(), v.isDeleted(),
                v.getOriginVersionId());
    }

    private static Dtos.ObjectMetaResponse toMetaResponse(ObjectMeta m) {
        List<Dtos.VersionResponse> versions = m.getVersions().stream()
                .map(StorageController::toVersionResponse)
                .toList();
        return new Dtos.ObjectMetaResponse(m.getKey(), m.getCurrentVersionId(), versions);
    }
}
