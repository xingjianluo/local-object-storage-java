package com.github.xingjianluo.localobjectstoragejava.tests.storage;

import com.github.xingjianluo.localobjectstoragejava.storage.LocalContentStore;
import com.github.xingjianluo.localobjectstoragejava.storage.LocalObjectStorageService;
import com.github.xingjianluo.localobjectstoragejava.storage.LocalUploadSessionManager;
import com.github.xingjianluo.localobjectstoragejava.storage.ObjectStorageService;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 版本链与内容去重测试：多版本、按版本读取/删除、回滚、相同内容只存一份、引用计数一致。
 */
class VersionAndDedupTest {

    private static final Logger log = LoggerFactory.getLogger(VersionAndDedupTest.class);

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

    /** 通过完整的上传流程写入一个对象版本，返回 versionId。 */
    private String upload(String objectKey, byte[] content) {
        var spec = new UploadSessionManager.PartSpec(1, content.length, sha256(content));
        String sessionId = sessionManager.createSession(objectKey, List.of(spec));
        sessionManager.uploadPart(sessionId, 1, new ByteArrayInputStream(content), content.length);
        String versionId = sessionManager.complete(sessionId);
        log.info("[测试] 上传完成 object={} versionId={} sha256={}", objectKey, versionId, sha256(content));
        return versionId;
    }

    private byte[] readLatest(String objectKey) throws Exception {
        var data = objectStorageService.readLatest(objectKey);
        try (var in = data.stream()) {
            return in.readAllBytes();
        }
    }

    private byte[] readVersion(String objectKey, String versionId) throws Exception {
        var data = objectStorageService.readVersion(objectKey, versionId);
        try (var in = data.stream()) {
            return in.readAllBytes();
        }
    }

    @Test
    void multipleUploadsCreateVersionChain() throws Exception {
        String v1 = upload("doc.txt", bytes("v1-content"));
        String v2 = upload("doc.txt", bytes("v2-content"));
        String v3 = upload("doc.txt", bytes("v3-content"));

        List<ObjectStorageService.VersionInfo> versions = objectStorageService.listVersions("doc.txt");
        assertEquals(3, versions.size());
        // 新到旧排序
        assertEquals(v3, versions.get(0).versionId());
        assertEquals(v2, versions.get(1).versionId());
        assertEquals(v1, versions.get(2).versionId());
        log.info("[测试] 版本链={}", versions.stream().map(ObjectStorageService.VersionInfo::versionId).toList());

        assertArrayEquals(bytes("v3-content"), readLatest("doc.txt"));
        assertArrayEquals(bytes("v1-content"), readVersion("doc.txt", v1));
        assertArrayEquals(bytes("v2-content"), readVersion("doc.txt", v2));
    }

    @Test
    void sameContentIsStoredOnceWithCorrectRefCount() throws Exception {
        byte[] content = bytes("shared-content");
        upload("obj-a", content);
        upload("obj-b", content);
        upload("obj-c", content);

        String contentId = sha256(content);
        // 底层只有一份数据
        long blobCount = Files.list(tempDir.resolve("blobs"))
                .filter(p -> !p.getFileName().toString().endsWith(".ref")).count();
        assertEquals(1, blobCount);
        // 引用计数 = 3（三个版本引用同一内容）
        assertEquals(3, contentStore.refCounts().get(contentId).intValue());
        log.info("[测试] 去重生效 contentId={} refCount={}", contentId, 3);

        // 删除一个对象后其余仍可读，引用计数递减
        objectStorageService.deleteObject("obj-a");
        assertEquals(2, contentStore.refCounts().get(contentId).intValue());
        assertArrayEquals(content, readLatest("obj-b"));
        assertArrayEquals(content, readLatest("obj-c"));
        log.info("[测试] 删除 obj-a 后 refCount={}，obj-b/obj-c 仍可读", 2);
    }

    @Test
    void deleteVersionKeepsOtherVersions() throws Exception {
        String v1 = upload("doc.txt", bytes("v1"));
        String v2 = upload("doc.txt", bytes("v2"));

        objectStorageService.deleteVersion("doc.txt", v2);
        // 最新版本回退到 v1
        assertArrayEquals(bytes("v1"), readLatest("doc.txt"));
        assertEquals(1, objectStorageService.listVersions("doc.txt").size());
        StorageException e = assertThrows(StorageException.class,
                () -> objectStorageService.readVersion("doc.txt", v2));
        assertEquals(StorageException.ErrorCode.VERSION_NOT_FOUND, e.getCode());
        log.info("[测试] 删除版本 v2 后最新版本为 v1，读 v2 被拒绝 code={}", e.getCode());

        // v2 内容不再被引用
        assertEquals(0, contentStore.refCounts().get(sha256(bytes("v2"))).intValue());
        assertEquals(1, contentStore.refCounts().get(sha256(bytes("v1"))).intValue());
        assertNotEquals(v1, v2);
    }

    @Test
    void rollbackCreatesNewVersionPointingToOldContent() throws Exception {
        String v1 = upload("doc.txt", bytes("original"));
        upload("doc.txt", bytes("modified"));

        String rollbackVersion = objectStorageService.rollback("doc.txt", v1);
        assertArrayEquals(bytes("original"), readLatest("doc.txt"));
        assertEquals(3, objectStorageService.listVersions("doc.txt").size());
        log.info("[测试] 回滚生成新版本 versionId={}，最新内容恢复为 original", rollbackVersion);

        // 回滚后 v1 的内容被两个版本引用
        assertEquals(2, contentStore.refCounts().get(sha256(bytes("original"))).intValue());
        assertEquals(1, contentStore.refCounts().get(sha256(bytes("modified"))).intValue());

        // 删除回滚版本后，v1 仍可读（引用计数不出错）
        objectStorageService.deleteVersion("doc.txt", rollbackVersion);
        assertArrayEquals(bytes("original"), readVersion("doc.txt", v1));
        assertEquals(1, contentStore.refCounts().get(sha256(bytes("original"))).intValue());
        log.info("[测试] 删除回滚版本后 v1 仍可读，refCount 恢复为 1");
    }

    @Test
    void deleteObjectReleasesAllVersions() throws Exception {
        upload("shared-x", bytes("common"));
        upload("shared-y", bytes("common"));
        upload("unique", bytes("unique-content"));

        objectStorageService.deleteObject("shared-x");
        assertEquals(1, contentStore.refCounts().get(sha256(bytes("common"))).intValue());

        objectStorageService.deleteObject("shared-y");
        assertEquals(0, contentStore.refCounts().get(sha256(bytes("common"))).intValue());
        assertEquals(1, contentStore.refCounts().get(sha256(bytes("unique-content"))).intValue());

        StorageException e = assertThrows(StorageException.class,
                () -> objectStorageService.readLatest("shared-y"));
        assertEquals(StorageException.ErrorCode.OBJECT_NOT_FOUND, e.getCode());
        log.info("[测试] 删除对象后引用计数正确释放，读取被拒绝 code={}", e.getCode());
    }

    @Test
    void gcSweepsUnreachableContentOnly() throws Exception {
        upload("keep", bytes("keep-me"));
        upload("drop", bytes("drop-me"));
        objectStorageService.deleteObject("drop");

        List<String> swept = contentStore.sweep();
        assertEquals(List.of(sha256(bytes("drop-me"))), swept);
        // 被回收内容读取明确失败
        StorageException e = assertThrows(StorageException.class,
                () -> contentStore.open(sha256(bytes("drop-me"))));
        assertEquals(StorageException.ErrorCode.CONTENT_MISSING, e.getCode());
        // 仍被引用的内容完好
        assertArrayEquals(bytes("keep-me"), readLatest("keep"));
        log.info("[测试] GC 只回收不可达内容 swept={}，keep 对象完整", swept);
    }

    @Test
    void readingWhileSweepingGetsCompleteData() throws Exception {
        byte[] content = bytes("pinned-content");
        upload("pinned", content);
        String contentId = sha256(content);

        // 打开读取流（加读钉），此时即使引用被释放也不能被回收
        var data = objectStorageService.readLatest("pinned");
        var stream = data.stream();
        objectStorageService.deleteObject("pinned");
        List<String> swept = contentStore.sweep();
        assertEquals(List.of(), swept, "有读钉的内容不得被回收");
        // 读取仍得到完整数据
        assertArrayEquals(content, stream.readAllBytes());
        stream.close();
        // 读钉释放后可回收
        List<String> sweptAfter = contentStore.sweep();
        assertEquals(List.of(contentId), sweptAfter);
        log.info("[测试] 读取期间内容不被回收，读取完整；关闭后回收 contentId={}", contentId);
    }
}
