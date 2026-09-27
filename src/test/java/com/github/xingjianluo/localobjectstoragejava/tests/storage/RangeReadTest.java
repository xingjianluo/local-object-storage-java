package com.github.xingjianluo.localobjectstoragejava.tests.storage;

import com.github.xingjianluo.localobjectstoragejava.storage.LocalContentStore;
import com.github.xingjianluo.localobjectstoragejava.storage.LocalObjectStorageService;
import com.github.xingjianluo.localobjectstoragejava.storage.LocalUploadSessionManager;
import com.github.xingjianluo.localobjectstoragejava.storage.ObjectStorageService;
import com.github.xingjianluo.localobjectstoragejava.storage.StorageController;
import com.github.xingjianluo.localobjectstoragejava.storage.StorageException;
import com.github.xingjianluo.localobjectstoragejava.storage.StorageProperties;
import com.github.xingjianluo.localobjectstoragejava.storage.UploadSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 范围读取测试：整包/按段边界、非法范围拒绝、历史版本按段读取、
 * 边读边删/边回收的交错一致性、大对象读取的内存上界。
 */
class RangeReadTest {

    private static final Logger log = LoggerFactory.getLogger(RangeReadTest.class);

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
        return uploadParts(objectKey, List.of(content));
    }

    /** 以多个分片上传一个对象，返回 versionId。 */
    private String uploadParts(String objectKey, List<byte[]> parts) {
        List<UploadSessionManager.PartSpec> specs = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            specs.add(new UploadSessionManager.PartSpec(i + 1, parts.get(i).length, sha256(parts.get(i))));
        }
        String sessionId = sessionManager.createSession(objectKey, specs);
        for (int i = 0; i < parts.size(); i++) {
            sessionManager.uploadPart(sessionId, i + 1,
                    new ByteArrayInputStream(parts.get(i)), parts.get(i).length);
        }
        return sessionManager.complete(sessionId);
    }

    private byte[] readRange(String key, long offset, long length) throws Exception {
        var data = objectStorageService.readLatestRange(key, offset, length);
        try (InputStream in = data.stream()) {
            return in.readAllBytes();
        }
    }

    @Test
    void rangeReadBoundariesAcrossParts() throws Exception {
        // 3 个分片：10 + 10 + 5 = 25 字节，内容逐字节可区分
        byte[] content = new byte[25];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) ('a' + i);
        }
        byte[] p1 = Arrays.copyOfRange(content, 0, 10);
        byte[] p2 = Arrays.copyOfRange(content, 10, 20);
        byte[] p3 = Arrays.copyOfRange(content, 20, 25);
        uploadParts("doc.bin", List.of(p1, p2, p3));

        // 整包
        var full = objectStorageService.readLatest("doc.bin");
        try (InputStream in = full.stream()) {
            assertArrayEquals(content, in.readAllBytes());
        }
        // 开头一段
        assertArrayEquals(Arrays.copyOfRange(content, 0, 5), readRange("doc.bin", 0, 5));
        // 结尾一段
        assertArrayEquals(Arrays.copyOfRange(content, 20, 25), readRange("doc.bin", 20, 5));
        // 跨分片边界（part1/part2 边界在 10，part2/part3 边界在 20）
        assertArrayEquals(Arrays.copyOfRange(content, 8, 22), readRange("doc.bin", 8, 14));
        // 单字节（分片边界上的字节）
        assertArrayEquals(new byte[]{content[10]}, readRange("doc.bin", 10, 1));
        assertArrayEquals(new byte[]{content[0]}, readRange("doc.bin", 0, 1));
        assertArrayEquals(new byte[]{content[24]}, readRange("doc.bin", 24, 1));

        // 长度超出末尾自动截断，hasMore=false
        var tail = objectStorageService.readLatestRange("doc.bin", 20, 1000);
        assertEquals(25, tail.totalSize());
        assertEquals(20, tail.offset());
        assertEquals(5, tail.length());
        assertFalse(tail.hasMore());
        try (InputStream in = tail.stream()) {
            assertArrayEquals(Arrays.copyOfRange(content, 20, 25), in.readAllBytes());
        }
        // 中间一段 hasMore=true
        var head = objectStorageService.readLatestRange("doc.bin", 0, 5);
        assertTrue(head.hasMore());
        assertEquals(5, head.length());
        head.stream().close();
        log.info("[测试] 范围读取边界（开头/结尾/跨分片/单字节/截断）全部通过");
    }

    @Test
    void historicalVersionRangeRead() throws Exception {
        byte[] v1Content = bytes("version-one-content-0123456789");
        byte[] v2Content = bytes("version-two-DIFFERENT-content!!");
        String v1 = upload("hist.txt", v1Content);
        upload("hist.txt", v2Content);

        // 历史版本按段读取
        var data = objectStorageService.readVersionRange("hist.txt", v1, 8, 3);
        assertEquals(v1Content.length, data.totalSize());
        try (InputStream in = data.stream()) {
            assertArrayEquals("one".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
        // 最新版本按段读取拿到的是 v2 的内容
        var latest = objectStorageService.readLatestRange("hist.txt", 8, 3);
        try (InputStream in = latest.stream()) {
            assertArrayEquals("two".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
        log.info("[测试] 历史版本与最新版本的范围读取互不干扰");
    }

    @Test
    void invalidRangesRejectedWithDistinctErrorCodes() {
        upload("obj.bin", bytes("0123456789"));

        // 非法范围：负偏移、零/负长度、起点越界
        assertRangeRejected("obj.bin", -1, 5);
        assertRangeRejected("obj.bin", 0, 0);
        assertRangeRejected("obj.bin", 3, -2);
        assertRangeRejected("obj.bin", 10, 1);   // offset == size
        assertRangeRejected("obj.bin", 100, 1);  // offset > size

        // 与对象不存在 / 版本不存在可区分
        StorageException notFound = assertThrows(StorageException.class,
                () -> objectStorageService.readLatestRange("missing.bin", 0, 1));
        assertEquals(StorageException.ErrorCode.OBJECT_NOT_FOUND, notFound.getCode());
        StorageException versionMissing = assertThrows(StorageException.class,
                () -> objectStorageService.readVersionRange("obj.bin", "no-such-version", 0, 1));
        assertEquals(StorageException.ErrorCode.VERSION_NOT_FOUND, versionMissing.getCode());

        // 空对象：任何范围都不可满足
        upload("empty.bin", new byte[0]);
        assertRangeRejected("empty.bin", 0, 1);
        log.info("[测试] 非法范围均以 RANGE_NOT_SATISFIABLE 拒绝，与 404 类错误可区分");
    }

    private void assertRangeRejected(String key, long offset, long length) {
        StorageException e = assertThrows(StorageException.class,
                () -> objectStorageService.readLatestRange(key, offset, length));
        assertEquals(StorageException.ErrorCode.RANGE_NOT_SATISFIABLE, e.getCode(),
                "offset=" + offset + " length=" + length);
    }

    @Test
    void rangeHeaderParsing() {
        // bytes=start-end
        assertArrayEquals(new long[]{2, 3}, StorageController.parseRangeHeader("bytes=2-4", 10));
        // bytes=start-
        assertArrayEquals(new long[]{8, 2}, StorageController.parseRangeHeader("bytes=8-", 10));
        // bytes=-suffix
        assertArrayEquals(new long[]{7, 3}, StorageController.parseRangeHeader("bytes=-3", 10));
        // 后缀超过总长 → 从头开始
        assertArrayEquals(new long[]{0, 10}, StorageController.parseRangeHeader("bytes=-100", 10));
        // 非法格式
        for (String bad : List.of("items=0-1", "bytes=", "bytes=abc", "bytes=5-2", "bytes=0-1,3-4", "bytes=-0")) {
            assertThrows(StorageException.class,
                    () -> StorageController.parseRangeHeader(bad, 10), "应拒绝: " + bad);
        }
        log.info("[测试] Range 头解析（含后缀范围与非法格式）通过");
    }

    @Test
    void readSurvivesDeleteAndSweepWhileOpen() throws Exception {
        byte[] content = bytes("content-that-must-survive-concurrent-delete");
        upload("victim.bin", content);

        // 打开范围流后删除对象并触发回收：读钉保护，流仍能读完整段
        var data = objectStorageService.readLatestRange("victim.bin", 8, 7);
        objectStorageService.deleteObject("victim.bin");
        assertTrue(contentStore.sweep().isEmpty(), "有读钉的内容不得被回收");
        try (InputStream in = data.stream()) {
            assertArrayEquals("that-mu".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
        // 流关闭后回收生效
        assertEquals(1, contentStore.sweep().size());

        // 删除后再读：对象不存在，明确失败
        StorageException e = assertThrows(StorageException.class,
                () -> objectStorageService.readLatestRange("victim.bin", 0, 1));
        assertEquals(StorageException.ErrorCode.OBJECT_NOT_FOUND, e.getCode());
        log.info("[测试] 边读边删+回收：打开的流读到完整数据，关闭后内容被回收");
    }

    @Test
    void interleavedRangeReadDeleteRollbackAndSweep() throws Exception {
        // 内容自描述：content[i] = (byte)(i * 31 + gen)，任一片段都能校验是否来自同一代
        int size = 256 * 1024;
        byte[] base = pattern(0, size);
        upload("hot.bin", base);

        int generations = 12;
        int readers = 4;
        int readsPerReader = 60;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger corrupted = new AtomicInteger();
        AtomicInteger readOk = new AtomicInteger();
        AtomicInteger readFailedClean = new AtomicInteger();
        Random random = new Random(42);

        List<Future<?>> futures = new ArrayList<>();
        // 写线程：逐代上传新版本
        futures.add(pool.submit(() -> {
            start.await();
            for (int g = 1; g <= generations; g++) {
                upload("hot.bin", pattern(g, size));
            }
            return null;
        }));
        // 清理线程：删除最老版本 + 回收
        futures.add(pool.submit(() -> {
            start.await();
            for (int i = 0; i < generations * 3; i++) {
                try {
                    var versions = objectStorageService.listVersions("hot.bin");
                    if (versions.size() > 2) {
                        objectStorageService.deleteVersion("hot.bin",
                                versions.get(versions.size() - 1).versionId());
                    }
                } catch (StorageException ignored) {
                    // 并发下合法失败
                }
                contentStore.sweep();
                Thread.sleep(2);
            }
            return null;
        }));
        // 读线程：随机范围读取并校验片段不自相矛盾
        for (int r = 0; r < readers; r++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < readsPerReader; i++) {
                    long offset = random.nextInt(size - 1024);
                    int length = 1 + random.nextInt(1024);
                    try {
                        var data = objectStorageService.readLatestRange("hot.bin", offset, length);
                        byte[] slice;
                        try (InputStream in = data.stream()) {
                            slice = in.readAllBytes();
                        }
                        if (slice.length != data.length() || !selfConsistent(slice, offset)) {
                            corrupted.incrementAndGet();
                        } else {
                            readOk.incrementAndGet();
                        }
                    } catch (StorageException e) {
                        // 允许明确失败，不允许拿到错数据
                        readFailedClean.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(0, corrupted.get(), "读取不得返回半截或拼接错位的内容");
        assertTrue(readOk.get() > 0, "应当有成功的读取");
        log.info("[测试] 读/写/删/回收交错：成功={} 明确失败={} 数据损坏={}",
                readOk.get(), readFailedClean.get(), corrupted.get());
    }

    private static byte[] pattern(int gen, int size) {
        byte[] content = new byte[size];
        for (int i = 0; i < size; i++) {
            content[i] = (byte) (i * 31 + gen);
        }
        return content;
    }

    /** 校验片段是否整体来自同一代内容（gen 由首字节反推）。 */
    private static boolean selfConsistent(byte[] slice, long offset) {
        if (slice.length == 0) {
            return true;
        }
        int gen = slice[0] - (byte) (offset * 31);
        for (int i = 0; i < slice.length; i++) {
            if (slice[i] != (byte) ((offset + i) * 31 + gen)) {
                return false;
            }
        }
        return true;
    }

    @Test
    void largeObjectReadKeepsMemoryBounded() throws Exception {
        // 96MB 对象（超过单分片上限需分两片）
        int partSize = 48 * 1024 * 1024;
        byte[] p1 = new byte[partSize];
        byte[] p2 = new byte[partSize];
        new Random(7).nextBytes(p1);
        new Random(8).nextBytes(p2);
        uploadParts("big.bin", List.of(p1, p2));
        p1 = null;
        p2 = null;

        System.gc();
        long heapBefore = usedHeap();
        // 整包流式读取
        var full = objectStorageService.readLatest("big.bin");
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = full.stream()) {
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
            }
        }
        assertEquals(96L * 1024 * 1024, total);
        // 末尾 1MB 范围读取
        var tail = objectStorageService.readLatestRange("big.bin", 95L * 1024 * 1024, 1024 * 1024);
        try (InputStream in = tail.stream()) {
            assertEquals(1024 * 1024, in.readAllBytes().length);
        }
        long heapGrowth = usedHeap() - heapBefore;
        log.info("[测试] 读取 96MB 对象期间堆增长 {} bytes", heapGrowth);
        assertTrue(heapGrowth < 32L * 1024 * 1024,
                "读取大对象的堆占用必须有界，实际增长 " + heapGrowth);
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
