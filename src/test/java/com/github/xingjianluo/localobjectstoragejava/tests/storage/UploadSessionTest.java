package com.github.xingjianluo.localobjectstoragejava.tests.storage;

import com.github.xingjianluo.localobjectstoragejava.storage.LocalContentStore;
import com.github.xingjianluo.localobjectstoragejava.storage.LocalObjectStorageService;
import com.github.xingjianluo.localobjectstoragejava.storage.LocalUploadSessionManager;
import com.github.xingjianluo.localobjectstoragejava.storage.StorageException;
import com.github.xingjianluo.localobjectstoragejava.storage.StorageProperties;
import com.github.xingjianluo.localobjectstoragejava.storage.UploadSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分片上传会话测试：乱序/重复分片、校验失败、编号越界、会话过期、重复完成、中止。
 */
class UploadSessionTest {

    private static final Logger log = LoggerFactory.getLogger(UploadSessionTest.class);

    @TempDir
    Path tempDir;

    private StorageProperties properties;
    private LocalContentStore contentStore;
    private LocalObjectStorageService objectStorageService;
    private LocalUploadSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        properties.setRootDir(tempDir.toString());
        properties.setSessionTtl(Duration.ofMinutes(5));
        contentStore = new LocalContentStore(properties);
        objectStorageService = new LocalObjectStorageService(properties, contentStore);
        sessionManager = new LocalUploadSessionManager(properties, contentStore, objectStorageService);
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<UploadSessionManager.PartSpec> specsOf(byte[]... parts) {
        List<UploadSessionManager.PartSpec> specs = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            specs.add(new UploadSessionManager.PartSpec(i + 1, parts[i].length, sha256(parts[i])));
        }
        return specs;
    }

    @Test
    void outOfOrderAndDuplicatePartsCompleteSuccessfully() throws Exception {
        byte[] p1 = bytes("hello-");
        byte[] p2 = bytes("object-");
        byte[] p3 = bytes("storage");
        String sessionId = sessionManager.createSession("a.txt", specsOf(p1, p2, p3));
        log.info("[测试] 创建会话 sessionId={} 分片数=3", sessionId, 3);

        // 乱序上传：3, 1, 2
        sessionManager.uploadPart(sessionId, 3, new ByteArrayInputStream(p3), p3.length);
        log.info("[测试] 上传分片 part=3 sha256={}", sha256(p3));
        sessionManager.uploadPart(sessionId, 1, new ByteArrayInputStream(p1), p1.length);
        log.info("[测试] 上传分片 part=1 sha256={}", sha256(p1));
        // 重复上传分片 3（断点续传重试），内容一致应幂等成功
        sessionManager.uploadPart(sessionId, 3, new ByteArrayInputStream(p3), p3.length);
        log.info("[测试] 重复上传分片 part=3（重试），应幂等成功");
        sessionManager.uploadPart(sessionId, 2, new ByteArrayInputStream(p2), p2.length);

        UploadSessionManager.SessionInfo info = sessionManager.sessionInfo(sessionId);
        assertEquals(List.of(1, 2, 3), info.receivedParts());
        log.info("[测试] 会话状态 receivedParts={}", info.receivedParts());

        String versionId = sessionManager.complete(sessionId);
        log.info("[测试] 合并完成 versionId={}", versionId);

        var data = objectStorageService.readLatest("a.txt");
        try (var in = data.stream()) {
            byte[] content = in.readAllBytes();
            assertArrayEquals(bytes("hello-object-storage"), content);
            log.info("[测试] 读取对象内容={} 校验一致", new String(content));
        }
    }

    @Test
    void checksumMismatchIsRejectedWithDistinctCode() {
        byte[] p1 = bytes("part-one");
        String sessionId = sessionManager.createSession("b.txt", specsOf(p1));
        byte[] corrupted = bytes("part-twp");
        log.info("[测试] 上传损坏分片 期望sha256={} 实际sha256={}", sha256(p1), sha256(corrupted));
        StorageException e = assertThrows(StorageException.class, () ->
                sessionManager.uploadPart(sessionId, 1, new ByteArrayInputStream(corrupted), corrupted.length));
        assertEquals(StorageException.ErrorCode.PART_CHECKSUM_MISMATCH, e.getCode());
        log.info("[测试] 校验失败被拒绝 code={} message={}", e.getCode(), e.getMessage());
    }

    @Test
    void outOfRangePartNumberIsRejected() {
        byte[] p1 = bytes("x");
        String sessionId = sessionManager.createSession("c.txt", specsOf(p1));
        StorageException e = assertThrows(StorageException.class, () ->
                sessionManager.uploadPart(sessionId, 2, new ByteArrayInputStream(p1), p1.length));
        assertEquals(StorageException.ErrorCode.PART_NUMBER_OUT_OF_RANGE, e.getCode());
        log.info("[测试] 编号越界被拒绝 code={}", e.getCode());
    }

    @Test
    void sizeMismatchIsRejected() {
        byte[] p1 = bytes("exact-size");
        String sessionId = sessionManager.createSession("d.txt", specsOf(p1));
        StorageException e = assertThrows(StorageException.class, () ->
                sessionManager.uploadPart(sessionId, 1, new ByteArrayInputStream(p1), p1.length + 1));
        assertEquals(StorageException.ErrorCode.PART_SIZE_MISMATCH, e.getCode());
        log.info("[测试] 大小不符被拒绝 code={}", e.getCode());
    }

    @Test
    void completeWithMissingPartsIsRejected() {
        byte[] p1 = bytes("one");
        byte[] p2 = bytes("two");
        String sessionId = sessionManager.createSession("e.txt", specsOf(p1, p2));
        sessionManager.uploadPart(sessionId, 1, new ByteArrayInputStream(p1), p1.length);
        StorageException e = assertThrows(StorageException.class, () -> sessionManager.complete(sessionId));
        assertEquals(StorageException.ErrorCode.PARTS_MISSING, e.getCode());
        log.info("[测试] 缺少分片时完成被拒绝 code={} message={}", e.getCode(), e.getMessage());
        // 未完成的上传不产生可见对象
        StorageException notFound = assertThrows(StorageException.class,
                () -> objectStorageService.readLatest("e.txt"));
        assertEquals(StorageException.ErrorCode.OBJECT_NOT_FOUND, notFound.getCode());
        log.info("[测试] 未完成上传不产生可见对象 code={}", notFound.getCode());
    }

    @Test
    void doubleCompleteIsRejected() {
        byte[] p1 = bytes("data");
        String sessionId = sessionManager.createSession("f.txt", specsOf(p1));
        sessionManager.uploadPart(sessionId, 1, new ByteArrayInputStream(p1), p1.length);
        sessionManager.complete(sessionId);
        StorageException e = assertThrows(StorageException.class, () -> sessionManager.complete(sessionId));
        assertEquals(StorageException.ErrorCode.SESSION_ALREADY_COMPLETED, e.getCode());
        log.info("[测试] 重复完成被拒绝 code={}", e.getCode());
    }

    @Test
    void abortedSessionRejectsFurtherOperations() {
        byte[] p1 = bytes("data");
        String sessionId = sessionManager.createSession("g.txt", specsOf(p1));
        sessionManager.abort(sessionId);
        StorageException e1 = assertThrows(StorageException.class, () ->
                sessionManager.uploadPart(sessionId, 1, new ByteArrayInputStream(p1), p1.length));
        assertEquals(StorageException.ErrorCode.SESSION_ALREADY_ABORTED, e1.getCode());
        StorageException e2 = assertThrows(StorageException.class, () -> sessionManager.complete(sessionId));
        assertEquals(StorageException.ErrorCode.SESSION_ALREADY_ABORTED, e2.getCode());
        log.info("[测试] 中止后上传/完成均被拒绝 code={}", e1.getCode());
    }

    @Test
    void expiredSessionIsRejectedAndReclaimed() throws Exception {
        properties.setSessionTtl(Duration.ofMillis(50));
        LocalUploadSessionManager shortTtlManager =
                new LocalUploadSessionManager(properties, contentStore, objectStorageService);
        byte[] p1 = bytes("expire-me");
        String sessionId = shortTtlManager.createSession("h.txt", specsOf(p1));
        log.info("[测试] 创建短TTL会话 sessionId={} ttl=50ms", sessionId);
        Thread.sleep(120);
        StorageException e = assertThrows(StorageException.class, () ->
                shortTtlManager.uploadPart(sessionId, 1, new ByteArrayInputStream(p1), p1.length));
        assertEquals(StorageException.ErrorCode.SESSION_EXPIRED, e.getCode());
        log.info("[测试] 过期会话上传被拒绝 code={}", e.getCode());

        List<String> expired = shortTtlManager.expireSessions();
        assertTrue(expired.contains(sessionId));
        log.info("[测试] 过期会话被回收 expired={}", expired);
    }

    @Test
    void unknownSessionIsRejected() {
        StorageException e = assertThrows(StorageException.class, () ->
                sessionManager.uploadPart("no-such-session", 1,
                        new ByteArrayInputStream(bytes("x")), 1));
        assertEquals(StorageException.ErrorCode.SESSION_NOT_FOUND, e.getCode());
        log.info("[测试] 未知会话被拒绝 code={}", e.getCode());
    }
}
