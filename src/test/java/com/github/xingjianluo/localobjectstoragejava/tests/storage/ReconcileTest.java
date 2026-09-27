package com.github.xingjianluo.localobjectstoragejava.tests.storage;

import com.github.xingjianluo.localobjectstoragejava.storage.LocalContentStore;
import com.github.xingjianluo.localobjectstoragejava.storage.LocalObjectStorageService;
import com.github.xingjianluo.localobjectstoragejava.storage.LocalUploadSessionManager;
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
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动对账测试：模拟进程在写入/合并中途被强杀后的磁盘残留，
 * 验证重启后的对账既不误删被引用内容，也能收回无引用残留。
 */
class ReconcileTest {

    private static final Logger log = LoggerFactory.getLogger(ReconcileTest.class);

    @TempDir
    Path tempDir;

    private StorageProperties properties;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        properties.setRootDir(tempDir.toString());
        properties.setSessionTtl(Duration.ofMinutes(5));
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

    private String upload(LocalUploadSessionManager sessionManager, String objectKey, byte[] content) {
        var spec = new UploadSessionManager.PartSpec(1, content.length, sha256(content));
        String sessionId = sessionManager.createSession(objectKey, List.of(spec));
        sessionManager.uploadPart(sessionId, 1, new ByteArrayInputStream(content), content.length);
        return sessionManager.complete(sessionId);
    }

    @Test
    void reconcileAfterCrashCleansOrphansAndKeepsReferenced() throws Exception {
        byte[] kept = bytes("content-still-referenced");
        byte[] leaked = bytes("content-leaked-by-crash");
        String keptId = sha256(kept);
        String leakedId = sha256(leaked);

        // 第一次“进程”：正常上传一个对象（kept 被版本引用）
        LocalContentStore store1 = new LocalContentStore(properties);
        LocalObjectStorageService objects1 = new LocalObjectStorageService(properties, store1);
        LocalUploadSessionManager sessions1 =
                new LocalUploadSessionManager(properties, store1, objects1);
        upload(sessions1, "alive.txt", kept);

        // 模拟崩溃窗口留下的残留：
        // a) put 落盘后、计数/提交前崩溃 → blobs 里多一个无引用的内容
        Files.write(tempDir.resolve("blobs").resolve(leakedId), leaked);
        // b) retain 之后、元数据落盘前崩溃 → kept 的引用计数虚高
        Files.writeString(tempDir.resolve("blobs").resolve(keptId + ".ref"), "5");
        // c) 写入中断的临时文件
        Files.writeString(tempDir.resolve("tmp").resolve("put-123.part"), "partial");
        // d) 会话目录里的分片临时文件与元数据临时文件
        Path sessionDir = Files.createDirectories(tempDir.resolve("sessions").resolve("sid-crashed"));
        Files.writeString(sessionDir.resolve("upload-456.part"), "partial-part");
        Files.writeString(sessionDir.resolve("session.json.tmp"), "{}");
        // e) 对象元数据临时文件
        String encodedKey = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("alive.txt".getBytes(StandardCharsets.UTF_8));
        Files.writeString(tempDir.resolve("objects").resolve(encodedKey).resolve("meta.json.tmp"), "{}");

        // 第二次“进程”：重启加载 + 对账 + 回收（与 StorageReconciler 启动流程一致）
        LocalContentStore store2 = new LocalContentStore(properties);
        LocalObjectStorageService objects2 = new LocalObjectStorageService(properties, store2);
        new LocalUploadSessionManager(properties, store2, objects2);
        store2.reconcile(objects2.contentReferences());
        store2.sweep();

        // 被引用的内容：保留，且引用计数被校正为真实值 1
        assertTrue(Files.exists(tempDir.resolve("blobs").resolve(keptId)), "被引用内容不得误删");
        assertEquals(1, store2.refCounts().get(keptId).intValue(), "虚高的引用计数应被校正");
        // 无引用残留：回收
        assertFalse(Files.exists(tempDir.resolve("blobs").resolve(leakedId)), "无引用残留必须回收");
        assertFalse(store2.refCounts().containsKey(leakedId));
        // 临时文件全部清理
        try (var tmpFiles = Files.list(tempDir.resolve("tmp"))) {
            assertEquals(0, tmpFiles.count(), "tmp 目录应清空");
        }
        assertFalse(Files.exists(sessionDir.resolve("upload-456.part")));
        assertFalse(Files.exists(sessionDir.resolve("session.json.tmp")));
        assertFalse(Files.exists(tempDir.resolve("objects").resolve(encodedKey).resolve("meta.json.tmp")));

        // 对账后对象仍可完整读出
        var data = objects2.readLatest("alive.txt");
        try (var in = data.stream()) {
            assertEquals("content-still-referenced",
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        log.info("[测试] 崩溃重启对账：引用内容保留(refCount校正为1)，残留回收，临时文件清理");
    }

    @Test
    void reconcileDropsRefCountForContentNoVersionReferences() throws Exception {
        byte[] committed = bytes("committed-content");
        String committedId = sha256(committed);

        // 第一次“进程”：正常提交一个版本
        LocalContentStore store1 = new LocalContentStore(properties);
        LocalObjectStorageService objects1 = new LocalObjectStorageService(properties, store1);
        LocalUploadSessionManager sessions1 =
                new LocalUploadSessionManager(properties, store1, objects1);
        upload(sessions1, "obj.txt", committed);

        // 模拟“complete 中 put 成功、commitVersion 前崩溃”：同一内容多计了一次引用
        // （put +1、retain +1、release -1，崩溃点在 release 之前 → 计数 2，实际版本只引用 1 次；
        //  更极端的情况是崩溃在 commitVersion 之前 → 计数 1 但无任何版本引用）
        Files.writeString(tempDir.resolve("blobs").resolve(committedId + ".ref"), "2");

        // 重启对账：以版本链为准校正为 1，内容保留
        LocalContentStore store2 = new LocalContentStore(properties);
        LocalObjectStorageService objects2 = new LocalObjectStorageService(properties, store2);
        store2.reconcile(objects2.contentReferences());
        store2.sweep();
        assertEquals(1, store2.refCounts().get(committedId).intValue());
        assertTrue(Files.exists(tempDir.resolve("blobs").resolve(committedId)));

        // 再模拟“对象已删除但 release 前崩溃”：版本链为空但计数为 1
        objects2.deleteObject("obj.txt");
        Files.writeString(tempDir.resolve("blobs").resolve(committedId + ".ref"), "1");
        LocalContentStore store3 = new LocalContentStore(properties);
        LocalObjectStorageService objects3 = new LocalObjectStorageService(properties, store3);
        store3.reconcile(objects3.contentReferences());
        store3.sweep();
        assertFalse(Files.exists(tempDir.resolve("blobs").resolve(committedId)),
                "无版本引用的内容必须被回收");
        assertFalse(store3.refCounts().containsKey(committedId));
        log.info("[测试] 对账按版本链校正引用计数：虚高校正为1，无引用内容回收");
    }
}
