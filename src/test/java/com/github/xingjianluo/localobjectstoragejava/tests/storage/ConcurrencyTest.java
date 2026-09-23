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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发一致性测试：并发上传相同内容、上传与读取交错、删除与回滚交错。
 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

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

    private String upload(String objectKey, byte[] content) {
        var spec = new UploadSessionManager.PartSpec(1, content.length, sha256(content));
        String sessionId = sessionManager.createSession(objectKey, List.of(spec));
        sessionManager.uploadPart(sessionId, 1, new ByteArrayInputStream(content), content.length);
        return sessionManager.complete(sessionId);
    }

    @Test
    void concurrentUploadsOfSameContentDeduplicate() throws Exception {
        int threads = 16;
        byte[] content = bytes("identical-concurrent-content");
        String contentId = sha256(content);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "obj-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return upload(key, content);
            }));
        }
        start.countDown();
        for (Future<String> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 底层只有一份数据，引用计数 = 线程数
        long blobCount = Files.list(tempDir.resolve("blobs"))
                .filter(p -> !p.getFileName().toString().endsWith(".ref")).count();
        assertEquals(1, blobCount);
        assertEquals(threads, contentStore.refCounts().get(contentId).intValue());
        log.info("[测试] {} 个并发上传相同内容，去重为 1 份，refCount={}", threads, threads);

        // 每个对象都可读出完整内容
        for (int i = 0; i < threads; i++) {
            var data = objectStorageService.readLatest("obj-" + i);
            try (var in = data.stream()) {
                assertArrayEquals(content, in.readAllBytes());
            }
        }
        log.info("[测试] 全部 {} 个对象读取一致", threads);
    }

    @Test
    void interleavedUploadAndReadStayConsistent() throws Exception {
        upload("hot.txt", bytes("gen-0"));
        int writers = 4;
        int readers = 4;
        int generations = 20;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        AtomicInteger readFailures = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            int base = w * generations;
            futures.add(pool.submit(() -> {
                start.await();
                for (int g = 1; g <= generations; g++) {
                    upload("hot.txt", bytes("gen-" + (base + g)));
                }
                return null;
            }));
        }
        for (int r = 0; r < readers; r++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < generations * writers; i++) {
                    var data = objectStorageService.readLatest("hot.txt");
                    try (var in = data.stream()) {
                        byte[] content = in.readAllBytes();
                        // 读到的必须是某一代完整内容
                        String s = new String(content, StandardCharsets.UTF_8);
                        if (!s.startsWith("gen-")) {
                            readFailures.incrementAndGet();
                        }
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(0, readFailures.get(), "读取不得得到半成品内容");
        int expectedVersions = 1 + writers * generations;
        assertEquals(expectedVersions, objectStorageService.listVersions("hot.txt").size());
        // 每个版本引用各自内容，引用计数总和 = 版本数
        int totalRefs = contentStore.refCounts().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(expectedVersions, totalRefs);
        log.info("[测试] 上传/读取交错完成：版本数={} 引用计数总和={} 读失败={}",
                expectedVersions, totalRefs, readFailures.get());
    }

    @Test
    void interleavedDeleteAndRollbackKeepRefCountsConsistent() throws Exception {
        byte[] shared = bytes("shared-base");
        String v1 = upload("rb.txt", shared);
        upload("rb.txt", bytes("other-content"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger rollbackSuccess = new AtomicInteger();
        AtomicInteger deleteSuccess = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    String newVersion = objectStorageService.rollback("rb.txt", v1);
                    rollbackSuccess.incrementAndGet();
                    // 立即删除刚回滚产生的版本
                    objectStorageService.deleteVersion("rb.txt", newVersion);
                    deleteSuccess.incrementAndGet();
                } catch (Exception e) {
                    // 允许并发下的合法失败，但最终状态必须一致
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 最终：v1(shared) 与 v2(other) 各被引用一次，不得有残留或负计数
        String sharedId = sha256(shared);
        String otherId = sha256(bytes("other-content"));
        assertEquals(1, contentStore.refCounts().get(sharedId).intValue());
        assertEquals(1, contentStore.refCounts().get(otherId).intValue());
        assertEquals(2, objectStorageService.listVersions("rb.txt").size());
        var data = objectStorageService.readVersion("rb.txt", v1);
        try (var in = data.stream()) {
            assertArrayEquals(shared, in.readAllBytes());
        }
        log.info("[测试] 删除/回滚交错：rollback成功={} delete成功={} 最终refCount shared={} other={}",
                rollbackSuccess.get(), deleteSuccess.get(), 1, 1);

        // GC 后不得误删仍被引用的内容
        contentStore.sweep();
        assertTrue(Files.exists(tempDir.resolve("blobs").resolve(sharedId)));
        assertTrue(Files.exists(tempDir.resolve("blobs").resolve(otherId)));
    }
}
