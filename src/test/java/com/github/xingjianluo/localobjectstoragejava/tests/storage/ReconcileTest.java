package com.github.xingjianluo.localobjectstoragejava.tests.storage;

import com.github.xingjianluo.localobjectstoragejava.storage.ContentStore;
import com.github.xingjianluo.localobjectstoragejava.storage.LocalContentStore;
import com.github.xingjianluo.localobjectstoragejava.storage.LocalObjectStorageService;
import com.github.xingjianluo.localobjectstoragejava.storage.LocalUploadSessionManager;
import com.github.xingjianluo.localobjectstoragejava.storage.StorageProperties;
import com.github.xingjianluo.localobjectstoragejava.storage.StorageReconciler;
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
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动对账测试：模拟进程在写入/合并/删除中途被强杀，重启后
 * 引用计数被修正、无引用残留被回收、被引用内容绝不误删、缺失内容明确报告。
 */
class ReconcileTest {

    private static final Logger log = LoggerFactory.getLogger(ReconcileTest.class);

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
        restart();
    }

    /** 重建全部组件，模拟进程重启（磁盘状态保留）。 */
    private void restart() {
        contentStore = new LocalContentStore(properties);
        objectStorageService = new LocalObjectStorageService(properties, contentStore);
        sessionManager = new LocalUploadSessionManager(properties, contentStore, objectStorageService);
    }

    private ContentStore.ReconcileReport reconcile() {
        ContentStore.ReconcileReport report =
                contentStore.reconcile(objectStorageService.contentReferences());
        log.info("[测试] 对账报告 orphans={} missing={} corrected={} tmp={}",
                report.removedOrphans(), report.missingContents(),
                report.correctedRefCounts(), report.removedTmpFiles());
        return report;
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

    private String upload(String objectKey, byte[] content) {
        var spec = new UploadSessionManager.PartSpec(1, content.length, sha256(content));
        String sessionId = sessionManager.createSession(objectKey, List.of(spec));
        sessionManager.uploadPart(sessionId, 1, new ByteArrayInputStream(content), content.length);
        return sessionManager.complete(sessionId);
    }

    private Path blobPath(String contentId) {
        return tempDir.resolve("blobs").resolve(contentId);
    }

    @Test
    void orphanContentFromCrashedMergeIsRemoved() throws Exception {
        // 模拟崩溃发生在 put 之后、commitVersion 之前：内容已落盘但没有任何版本引用
        String orphanId = contentStore.put(new ByteArrayInputStream(bytes("orphan")), 6);
        assertTrue(Files.exists(blobPath(orphanId)));
        assertEquals(1, contentStore.refCounts().get(orphanId));

        restart();
        var report = reconcile();

        assertEquals(List.of(orphanId), report.removedOrphans());
        assertFalse(Files.exists(blobPath(orphanId)), "无引用残留必须被回收");
        assertFalse(Files.exists(tempDir.resolve("blobs").resolve(orphanId + ".ref")));
        assertTrue(contentStore.refCounts().isEmpty());
        log.info("[测试] 合并中途崩溃的残留内容已回收");
    }

    @Test
    void refCountDriftIsCorrected() throws Exception {
        byte[] content = bytes("important-data");
        upload("obj", content);
        String contentId = sha256(content);

        // 模拟崩溃发生在 retain 之后、release 之前：引用计数被抬高
        contentStore.retain(contentId);
        assertEquals(2, contentStore.refCounts().get(contentId));

        restart();
        assertEquals(2, contentStore.refCounts().get(contentId), "重启后漂移的计数被加载");
        var report = reconcile();

        assertEquals(List.of(contentId), report.correctedRefCounts());
        assertEquals(1, contentStore.refCounts().get(contentId), "计数应修正为版本实际引用数");
        assertTrue(Files.exists(blobPath(contentId)), "被引用的内容绝不能误删");
        assertTrue(report.removedOrphans().isEmpty());

        // 修正后读取正常
        var data = objectStorageService.readLatest("obj");
        try (var in = data.stream()) {
            assertArrayEquals(content, in.readAllBytes());
        }
        log.info("[测试] 引用计数漂移已修正，内容保留且可读");
    }

    @Test
    void referencedContentIsNeverRemoved() throws Exception {
        byte[] c1 = bytes("content-one");
        byte[] c2 = bytes("content-two");
        upload("obj-a", c1);
        upload("obj-b", c2);
        upload("obj-a", c1); // 第二个版本引用同一内容

        restart();
        var report = reconcile();

        assertTrue(report.removedOrphans().isEmpty());
        assertTrue(report.correctedRefCounts().isEmpty());
        assertTrue(report.missingContents().isEmpty());
        assertEquals(2, contentStore.refCounts().get(sha256(c1)), "两个版本引用同一内容");
        assertEquals(1, contentStore.refCounts().get(sha256(c2)));
        assertTrue(Files.exists(blobPath(sha256(c1))));
        assertTrue(Files.exists(blobPath(sha256(c2))));
        log.info("[测试] 被引用内容在对账后完好");
    }

    @Test
    void missingContentIsReportedNotSilentlyIgnored() throws Exception {
        byte[] content = bytes("will-be-lost");
        upload("obj", content);
        String contentId = sha256(content);

        // 模拟磁盘故障：底层内容丢失但元数据还在
        Files.delete(blobPath(contentId));
        restart();
        var report = reconcile();

        assertEquals(List.of(contentId), report.missingContents());
        assertTrue(report.removedOrphans().isEmpty(), "被引用的内容即使缺失也不能按残留清理");
        log.info("[测试] 缺失内容已明确报告");
    }

    @Test
    void leftoverTmpFilesAreCleaned() throws Exception {
        // 模拟写入中途崩溃遗留的临时文件
        Path tmpDir = tempDir.resolve("tmp");
        Files.createDirectories(tmpDir);
        Files.writeString(tmpDir.resolve("put-abc123.part"), "partial-data");
        Files.writeString(tmpDir.resolve("put-def456.part"), "more-partial");

        restart();
        var report = reconcile();

        assertEquals(2, report.removedTmpFiles());
        assertTrue(Files.list(tmpDir).toList().isEmpty(), "临时目录必须清空");
        log.info("[测试] 中断遗留的临时文件已清理");
    }

    @Test
    void orphanedRefFileWithoutBlobIsRemoved() throws Exception {
        // 模拟崩溃发生在 blob 删除之后、.ref 删除之前（或反向写入失败）
        Files.createDirectories(tempDir.resolve("blobs"));
        Files.writeString(tempDir.resolve("blobs").resolve("deadbeef.ref"), "0");

        restart();
        var report = reconcile();

        assertEquals(List.of("deadbeef"), report.removedOrphans());
        assertFalse(Files.exists(tempDir.resolve("blobs").resolve("deadbeef.ref")));
        log.info("[测试] 孤立 .ref 文件已清理");
    }

    @Test
    void reconcilerBeanRunsEndToEnd() throws Exception {
        byte[] content = bytes("keep-me");
        upload("obj", content);
        String orphanId = contentStore.put(new ByteArrayInputStream(bytes("junk")), 4);

        restart();
        // 通过 ApplicationRunner 入口跑完整对账
        new StorageReconciler(objectStorageService, contentStore).run(null);

        assertFalse(Files.exists(blobPath(orphanId)));
        var data = objectStorageService.readRangeLatest("obj", 0, 3);
        try (var in = data.stream()) {
            assertArrayEquals(bytes("keep"), in.readAllBytes());
        }
        log.info("[测试] StorageReconciler 端到端通过");
    }
}
