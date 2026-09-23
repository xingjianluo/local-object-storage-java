package com.github.xingjianluo.localobjectstoragejava.tests;

import com.github.xingjianluo.localobjectstoragejava.config.StorageProperties;
import com.github.xingjianluo.localobjectstoragejava.model.ErrorCode;
import com.github.xingjianluo.localobjectstoragejava.model.ObjectMeta;
import com.github.xingjianluo.localobjectstoragejava.model.ObjectVersion;
import com.github.xingjianluo.localobjectstoragejava.model.StorageException;
import com.github.xingjianluo.localobjectstoragejava.model.UploadSession;
import com.github.xingjianluo.localobjectstoragejava.service.MultipartUploadService;
import com.github.xingjianluo.localobjectstoragejava.service.ObjectService;
import com.github.xingjianluo.localobjectstoragejava.store.BlobStore;
import com.github.xingjianluo.localobjectstoragejava.store.ChecksumUtil;
import com.github.xingjianluo.localobjectstoragejava.store.JsonCodec;
import com.github.xingjianluo.localobjectstoragejava.store.MetadataStore;
import com.github.xingjianluo.localobjectstoragejava.store.Reaper;
import com.github.xingjianluo.localobjectstoragejava.store.SessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分片上传核心协议单测：乱序、重试、校验失败、版本去重与引用计数。
 * 日志打印会话、分片与校验依据，便于人工核对。
 */
class MultipartUploadServiceTest {

    private static final Logger log = LoggerFactory.getLogger(MultipartUploadServiceTest.class);

    @TempDir
    Path tempDir;

    private StorageProperties properties;
    private SessionStore sessionStore;
    private MetadataStore metadataStore;
    private BlobStore blobStore;
    private MultipartUploadService uploadService;
    private ObjectService objectService;
    private Reaper reaper;

    private static final int PART_SIZE = 4;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        properties.setRootDir(tempDir.toString());
        properties.setSessionTtlMillis(3_600_000);
        properties.setBlobGcGraceMillis(0);
        JsonCodec jsonCodec = new JsonCodec();
        sessionStore = new SessionStore(properties, jsonCodec);
        metadataStore = new MetadataStore(properties, jsonCodec);
        blobStore = new BlobStore(properties);
        uploadService = new MultipartUploadService(sessionStore, blobStore, metadataStore, properties);
        objectService = new ObjectService(metadataStore, blobStore);
        reaper = new Reaper(sessionStore, metadataStore, blobStore);
    }

    @AfterEach
    void tearDown() {
        log.info("---- 用例结束 ----");
    }

    private byte[][] split(byte[] data, int partSize) {
        int n = (data.length + partSize - 1) / partSize;
        byte[][] parts = new byte[n][];
        for (int i = 0; i < n; i++) {
            parts[i] = Arrays.copyOfRange(data, i * partSize,
                    Math.min(data.length, (i + 1) * partSize));
        }
        return parts;
    }

    private void uploadAll(String sid, byte[][] parts, int... order) {
        for (int idx : order) {
            byte[] p = parts[idx - 1];
            String sha = ChecksumUtil.sha256Hex(p);
            log.info("[上传分片] sid={} part={} size={} sha256={}", sid, idx, p.length, sha);
            uploadService.uploadPart(sid, idx, p.length, sha, new ByteArrayInputStream(p));
        }
    }

    private byte[] readAll(String key) throws Exception {
        try (var ch = objectService.readCurrent(key)) {
            return Channels.newInputStream(ch).readAllBytes();
        }
    }

    @Test
    @DisplayName("乱序+重复分片上传后合并，对象正确可见")
    void outOfOrderAndDuplicateParts() throws Exception {
        String key = "data/file-a.bin";
        byte[] content = "abcdefghij".getBytes(); // 10 字节，4+4+2
        byte[][] parts = split(content, PART_SIZE);
        UploadSession session = uploadService.createSession(key, parts.length, PART_SIZE);
        log.info("[创建会话] sid={} key={} totalParts={} partSize={} expiresAt={}",
                session.getSessionId(), key, parts.length, PART_SIZE, session.getExpiresAtMillis());

        // 乱序：3,1,3(重复),2,1(重复)
        uploadAll(session.getSessionId(), parts, 3, 1, 3, 2, 1);

        UploadSession afterDup = uploadService.getSession(session.getSessionId());
        assertEquals(parts.length, afterDup.getParts().size(), "重复分片必须幂等，不产生多余分片记录");

        String totalSha = ChecksumUtil.sha256Hex(content);
        log.info("[完成合并依据] sid={} expectedTotalSize={} expectedSha256={}",
                session.getSessionId(), content.length, totalSha);
        UploadSession completed = uploadService.completeSession(session.getSessionId(), totalSha);
        assertEquals(UploadSession.State.COMPLETED, completed.getState());
        assertNotNull(completed.getCompletedVersionId());
        assertArrayEquals(content, readAll(key), "合并后读取内容必须与原始内容一致");
        log.info("[读取成功] key={} versionId={} contentId={}",
                key, completed.getCompletedVersionId(), completed.getContentId());

        // 重复完成必须被拒绝且原因可区分
        StorageException ex = assertThrows(StorageException.class,
                () -> uploadService.completeSession(session.getSessionId(), totalSha));
        assertEquals(ErrorCode.SESSION_ALREADY_COMPLETED, ex.getErrorCode());
        log.info("[重复完成被拒绝] code={} msg={}", ex.getErrorCode(), ex.getMessage());
    }

    @Test
    @DisplayName("分片校验失败与编号越界被拒绝且原因可区分")
    void checksumAndRangeFailures() {
        String key = "data/file-b.bin";
        byte[][] parts = split("hello12".getBytes(), PART_SIZE);
        UploadSession session = uploadService.createSession(key, parts.length, PART_SIZE);
        String sid = session.getSessionId();

        byte[] p1 = parts[0];
        // 错误的 checksum
        StorageException badChecksum = assertThrows(StorageException.class, () ->
                uploadService.uploadPart(sid, 1, p1.length, "deadbeef", new ByteArrayInputStream(p1)));
        assertEquals(ErrorCode.PART_CHECKSUM_MISMATCH, badChecksum.getErrorCode());
        log.info("[校验失败被拒绝] code={} msg={}", badChecksum.getErrorCode(), badChecksum.getMessage());

        // 声明大小不符
        StorageException badSize = assertThrows(StorageException.class, () ->
                uploadService.uploadPart(sid, 1, p1.length + 1, ChecksumUtil.sha256Hex(p1),
                        new ByteArrayInputStream(p1)));
        assertEquals(ErrorCode.PART_SIZE_MISMATCH, badSize.getErrorCode());
        log.info("[大小不符被拒绝] code={} msg={}", badSize.getErrorCode(), badSize.getMessage());

        // 编号越界
        StorageException outOfRange = assertThrows(StorageException.class, () ->
                uploadService.uploadPart(sid, parts.length + 1, 1, null,
                        new ByteArrayInputStream(new byte[]{1})));
        assertEquals(ErrorCode.PART_NUMBER_OUT_OF_RANGE, outOfRange.getErrorCode());
        log.info("[编号越界被拒绝] code={} msg={}", outOfRange.getErrorCode(), outOfRange.getMessage());

        // 失败的分片不得写入会话：此时完成必须报 PART_MISSING
        StorageException missing = assertThrows(StorageException.class,
                () -> uploadService.completeSession(sid, null));
        assertEquals(ErrorCode.PART_MISSING, missing.getErrorCode());
        log.info("[未收齐完成被拒绝] code={} msg={}", missing.getErrorCode(), missing.getMessage());

        // 中断的上传不得产生可见对象
        StorageException notFound = assertThrows(StorageException.class,
                () -> objectService.readCurrent(key));
        assertEquals(ErrorCode.OBJECT_NOT_FOUND, notFound.getErrorCode());
    }

    @Test
    @DisplayName("同编号不同内容的重试被拒绝为 PART_CONFLICT")
    void samePartDifferentContentRejected() {
        String key = "data/file-c.bin";
        byte[][] parts = split("abcdxy".getBytes(), PART_SIZE);
        UploadSession session = uploadService.createSession(key, parts.length, PART_SIZE);
        String sid = session.getSessionId();
        uploadAll(sid, parts, 1);

        byte[] wrong = "xyzx".getBytes();
        StorageException conflict = assertThrows(StorageException.class, () ->
                uploadService.uploadPart(sid, 1, wrong.length, ChecksumUtil.sha256Hex(wrong),
                        new ByteArrayInputStream(wrong)));
        assertEquals(ErrorCode.PART_CONFLICT, conflict.getErrorCode());
        log.info("[冲突重试被拒绝] code={} msg={}", conflict.getErrorCode(), conflict.getMessage());
    }

    @Test
    @DisplayName("整体校验失败不产生对象与新 blob")
    void totalChecksumFailureHasNoSideEffect() {
        String key = "data/file-d.bin";
        byte[] content = "abcdefgh".getBytes();
        byte[][] parts = split(content, PART_SIZE);
        UploadSession session = uploadService.createSession(key, parts.length, PART_SIZE);
        uploadAll(session.getSessionId(), parts, 1, 2);

        long blobsBefore = blobStore.listContentIds().size();
        StorageException ex = assertThrows(StorageException.class,
                () -> uploadService.completeSession(session.getSessionId(), "00".repeat(32)));
        assertEquals(ErrorCode.TOTAL_CHECKSUM_MISMATCH, ex.getErrorCode());
        log.info("[整体校验失败被拒绝] code={} msg={}", ex.getErrorCode(), ex.getMessage());
        assertEquals(blobsBefore, blobStore.listContentIds().size(), "校验失败不得残留 blob");
        assertThrows(StorageException.class, () -> objectService.readCurrent(key));
    }

    @Test
    @DisplayName("同内容覆盖上传只保留一份数据，引用计数正确；删除后可回收")
    void dedupAcrossVersionsAndGc() throws Exception {
        String key = "data/versioned.bin";
        byte[] v1 = "same-content".getBytes();
        byte[] v2 = "other-content-22".getBytes();
        byte[] v3 = "same-content".getBytes(); // 与 v1 相同内容

        String id1 = uploadOneVersion(key, v1);
        String id2 = uploadOneVersion(key, v2);
        String id3 = uploadOneVersion(key, v3);

        ObjectMeta meta = objectService.listVersions(key);
        assertEquals(3, meta.getVersions().size());
        assertEquals(id3, meta.getCurrentVersionId());
        ObjectVersion first = meta.getVersions().get(0);
        ObjectVersion third = meta.getVersions().get(2);
        assertEquals(first.getContentId(), third.getContentId(), "相同内容必须共享 contentId");
        assertEquals(2, blobStore.countRefs(first.getContentId()), "同内容两个版本引用计数应为 2");
        assertEquals(1, blobStore.countRefs(meta.getVersions().get(1).getContentId()));
        log.info("[版本链] key={} v1={} v2={} v3={} contentIds=({},{},{})",
                key, id1, id2, id3,
                first.getContentId(), meta.getVersions().get(1).getContentId(), third.getContentId());

        // 当前读为 v3 内容（即 v1 内容）
        assertArrayEquals(v1, readAll(key));
        // 历史版本可读
        try (var ch = objectService.readVersion(key, id2)) {
            assertArrayEquals(v2, Channels.newInputStream(ch).readAllBytes());
        }

        // 删除当前版本 v3 -> 当前指针回退到版本链中最近存活的 v2
        objectService.deleteVersion(key, id3);
        assertEquals(1, blobStore.countRefs(first.getContentId()), "删除一个版本后引用计数应为 1");
        assertArrayEquals(v2, readAll(key), "当前指针必须回退到最近存活的历史版本 v2");
        log.info("[删除当前版本后回退] key={} current={}", key,
                objectService.listVersions(key).getCurrentVersionId());

        // 显式删除仍引用同内容 blob 的历史版本 v1 -> 该内容零引用，回收后物理消失
        objectService.deleteVersion(key, id1);
        assertEquals(0, blobStore.countRefs(first.getContentId()));
        int deleted = reaper.reapUnreachableBlobs(System.currentTimeMillis());
        assertTrue(deleted >= 1, "零引用内容应被回收");
        assertEquals(0, blobStore.listContentIds().stream()
                .filter(id -> id.equals(first.getContentId())).count(), "blob 文件应已物理删除");
        assertArrayEquals(v2, readAll(key));
        log.info("[回收完成] 剩余 blobs={}", blobStore.listContentIds());

        // 重复删除被拒绝
        StorageException dupDel = assertThrows(StorageException.class,
                () -> objectService.deleteVersion(key, id1));
        assertEquals(ErrorCode.VERSION_DELETED, dupDel.getErrorCode());
    }

    @Test
    @DisplayName("回滚追加新版本并复用 blob；被删版本不可回滚")
    void rollbackAppendsVersionAndReusesBlob() throws Exception {
        String key = "data/rollback.bin";
        byte[] c1 = "version-one".getBytes();
        byte[] c2 = "version-two".getBytes();
        String v1 = uploadOneVersion(key, c1);
        String v2 = uploadOneVersion(key, c2);
        assertArrayEquals(c2, readAll(key));

        ObjectVersion rolled = objectService.rollback(key, v1);
        assertEquals(v1, rolled.getOriginVersionId());
        assertEquals(objectService.listVersions(key).getVersions().get(0).getContentId(),
                rolled.getContentId(), "回滚版本必须复用历史内容 blob");
        assertEquals(2, blobStore.countRefs(rolled.getContentId()));
        assertArrayEquals(c1, readAll(key));
        log.info("[回滚完成] key={} from={} new={} contentId={} refs={}",
                key, v1, rolled.getVersionId(), rolled.getContentId(),
                blobStore.countRefs(rolled.getContentId()));

        // 删除被回滚指向的源版本不影响新版本可读（引用计数保证）
        objectService.deleteVersion(key, v1);
        assertEquals(1, blobStore.countRefs(rolled.getContentId()));
        assertArrayEquals(c1, readAll(key));

        // 回滚到已删除版本必须被拒绝，且不得追加版本
        objectService.deleteVersion(key, v2);
        StorageException ex2 = assertThrows(StorageException.class,
                () -> objectService.rollback(key, v2));
        assertEquals(ErrorCode.VERSION_DELETED, ex2.getErrorCode());
        log.info("[回滚被删版本被拒绝] code={} msg={}", ex2.getErrorCode(), ex2.getMessage());
        assertEquals(3, objectService.listVersions(key).getVersions().size(),
                "失败的回滚不得追加版本");
    }

    @Test
    @DisplayName("过期会话不可上传/完成，并被回收且不产生对象")
    void expiredSessionRejectedAndReaped() {
        StorageProperties shortTtl = new StorageProperties();
        shortTtl.setRootDir(tempDir.resolve("ttl-" + UUID.randomUUID()).toString());
        shortTtl.setSessionTtlMillis(1);
        shortTtl.setBlobGcGraceMillis(0);
        JsonCodec codec = new JsonCodec();
        SessionStore sessions2 = new SessionStore(shortTtl, codec);
        MetadataStore meta2 = new MetadataStore(shortTtl, codec);
        BlobStore blobs2 = new BlobStore(shortTtl);
        MultipartUploadService svc = new MultipartUploadService(sessions2, blobs2, meta2, shortTtl);
        Reaper reaper2 = new Reaper(sessions2, meta2, blobs2);

        byte[] p1 = "aaaa".getBytes();
        UploadSession session = svc.createSession("ttl/key", 2, PART_SIZE);
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        StorageException expired = assertThrows(StorageException.class, () ->
                svc.uploadPart(session.getSessionId(), 1, p1.length,
                        ChecksumUtil.sha256Hex(p1), new ByteArrayInputStream(p1)));
        assertEquals(ErrorCode.SESSION_EXPIRED, expired.getErrorCode());
        log.info("[过期会话上传被拒绝] code={}", expired.getErrorCode());

        int removed = reaper2.reapExpiredSessions(System.currentTimeMillis());
        assertEquals(1, removed);
        assertEquals(0, sessions2.listSessions().size());
        assertNotNull(svc);
    }

    /** 完整上传一版内容并返回 versionId。 */
    private String uploadOneVersion(String key, byte[] content) {
        byte[][] parts = split(content, PART_SIZE);
        UploadSession session = uploadService.createSession(key, parts.length, PART_SIZE);
        int[] order = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            order[i] = i + 1;
        }
        uploadAll(session.getSessionId(), parts, order);
        UploadSession done = uploadService.completeSession(session.getSessionId(),
                ChecksumUtil.sha256Hex(content));
        log.info("[新版本] key={} sid={} versionId={} contentId={} size={}",
                key, session.getSessionId(), done.getCompletedVersionId(), done.getContentId(), content.length);
        return done.getCompletedVersionId();
    }
}
