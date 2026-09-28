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

        // 第一类：范围写法本身非法 → INVALID_RANGE（客户端参数错误，不应原样重试）
        assertInvalidRange("obj.bin", -1, 5);   // 负偏移
        assertInvalidRange("obj.bin", 0, 0);    // 零长度
        assertInvalidRange("obj.bin", 3, -2);   // 负长度
        assertInvalidRange("obj.bin", 9, 0);    // 起点合法但长度为零
        assertInvalidRange("obj.bin", 3, -1);

        // 第二类：范围写法合法，但起点达到/超过对象长度 → RANGE_NOT_SATISFIABLE（对象增长后可重试）
        assertRangeNotSatisfiable("obj.bin", 10, 1);   // offset == size
        assertRangeNotSatisfiable("obj.bin", 100, 1);  // offset > size
        assertRangeNotSatisfiable("obj.bin", 10, 0);   // 起点越界优先于零长度（bytes=10- 同形）

        // 与对象不存在 / 版本不存在继续可区分
        StorageException notFound = assertThrows(StorageException.class,
                () -> objectStorageService.readLatestRange("missing.bin", 0, 1));
        assertEquals(StorageException.ErrorCode.OBJECT_NOT_FOUND, notFound.getCode());
        StorageException versionMissing = assertThrows(StorageException.class,
                () -> objectStorageService.readVersionRange("obj.bin", "no-such-version", 0, 1));
        assertEquals(StorageException.ErrorCode.VERSION_NOT_FOUND, versionMissing.getCode());

        // 空对象：起点必然越界（0 >= 0 == size），任何范围（含零长度）都归“不可满足”
        upload("empty.bin", new byte[0]);
        assertRangeNotSatisfiable("empty.bin", 0, 1);
        assertRangeNotSatisfiable("empty.bin", 0, 100);
        assertRangeNotSatisfiable("empty.bin", 0, 0);
        // 但负偏移仍是写法错误（与对象长度无关）
        assertInvalidRange("empty.bin", -1, 1);
        log.info("[测试] INVALID_RANGE / RANGE_NOT_SATISFIABLE / 404 三类失败稳定可区分");
    }

    private void assertInvalidRange(String key, long offset, long length) {
        StorageException e = assertThrows(StorageException.class,
                () -> objectStorageService.readLatestRange(key, offset, length));
        assertEquals(StorageException.ErrorCode.INVALID_RANGE, e.getCode(),
                "offset=" + offset + " length=" + length);
    }

    private void assertRangeNotSatisfiable(String key, long offset, long length) {
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
        // 语法非法 / 起止颠倒 / 零长度后缀 → INVALID_RANGE（400 类）
        for (String bad : List.of("items=0-1", "bytes=", "bytes=abc", "bytes=5-2",
                "bytes=0-1,3-4", "bytes=-0", "bytes=2-1")) {
            StorageException e = assertThrows(StorageException.class,
                    () -> StorageController.parseRangeHeader(bad, 10), "应拒绝: " + bad);
            assertEquals(StorageException.ErrorCode.INVALID_RANGE, e.getCode(),
                    "应判为写法非法: " + bad);
        }
        // 写法合法但起点越界：解析器照常给出 {offset, length}，由服务层判 RANGE_NOT_SATISFIABLE
        assertArrayEquals(new long[]{10, 0}, StorageController.parseRangeHeader("bytes=10-", 10));
        assertArrayEquals(new long[]{100, 10}, StorageController.parseRangeHeader("bytes=100-109", 10));
        log.info("[测试] Range 头解析：语法错误=INVALID_RANGE，起点越界交给服务层=416");
    }

    @Test
    void rangeAndFullResultsExposeUniformSegmentMetadata() throws Exception {
        byte[] content = bytes("0123456789"); // 10 字节
        upload("meta.bin", content);

        // 按段（未到末尾）：总长度、本次区间、hasMore 全部直接给出
        var mid = objectStorageService.readLatestRange("meta.bin", 2, 3);
        assertEquals(10, mid.totalSize());
        assertEquals(2, mid.offset());
        assertEquals(3, mid.length());
        assertTrue(mid.hasMore());
        assertEquals(10, mid.version().size());
        try (InputStream in = mid.stream()) {
            assertArrayEquals(bytes("234"), in.readAllBytes());
        }
        // 按段（恰好到末尾）：length 截断、hasMore=false
        var tail = objectStorageService.readLatestRange("meta.bin", 8, 100);
        assertEquals(10, tail.totalSize());
        assertEquals(8, tail.offset());
        assertEquals(2, tail.length());
        assertFalse(tail.hasMore());
        // 单字节段
        var one = objectStorageService.readLatestRange("meta.bin", 9, 1);
        assertEquals(1, one.length());
        assertFalse(one.hasMore());
        one.stream().close();

        // 整包读取与按段字段同语义：[0, totalSize)，hasMore=false
        var full = objectStorageService.readLatest("meta.bin");
        assertEquals(10, full.totalSize());
        assertEquals(0, full.offset());
        assertEquals(10, full.length());
        assertFalse(full.hasMore());
        try (InputStream in = full.stream()) {
            assertArrayEquals(content, in.readAllBytes());
        }

        // 历史版本的字段语义与最新版本一致
        byte[] old = bytes("abcdefghijklmnopqrstu"); // 21 字节
        String oldVersion = upload("meta.bin", old);
        var hist = objectStorageService.readVersionRange("meta.bin", oldVersion, 10, 50);
        assertEquals(21, hist.totalSize());
        assertEquals(10, hist.offset());
        assertEquals(11, hist.length());
        assertFalse(hist.hasMore());
        try (InputStream in = hist.stream()) {
            assertArrayEquals("klmnopqrstu".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
        var histFull = objectStorageService.readVersion("meta.bin", oldVersion);
        assertEquals(21, histFull.totalSize());
        assertEquals(0, histFull.offset());
        assertEquals(21, histFull.length());
        assertFalse(histFull.hasMore());
        log.info("[测试] 整包/按段、最新/历史版本的返回字段语义一致");
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
        // 预先准备若干代内容，供回滚线程在它们之间来回回滚（必须真实触发 rollback）
        int seedGenerations = 4;
        List<String> seededVersions = new ArrayList<>();
        seededVersions.add(objectStorageService.statLatest("hot.bin").versionId());
        for (int g = 1; g <= seedGenerations; g++) {
            seededVersions.add(upload("hot.bin", pattern(g, size)));
        }

        int generations = 12;
        int readers = 4;
        int readsPerReader = 80;
        ExecutorService pool = Executors.newFixedThreadPool(9);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger corrupted = new AtomicInteger();
        AtomicInteger readOk = new AtomicInteger();
        AtomicInteger readFailedClean = new AtomicInteger();
        AtomicInteger rollbackSuccess = new AtomicInteger();
        Random random = new Random(42);

        List<Future<?>> futures = new ArrayList<>();
        // 写线程：逐代上传新版本
        futures.add(pool.submit(() -> {
            start.await();
            for (int g = seedGenerations + 1; g <= seedGenerations + generations; g++) {
                upload("hot.bin", pattern(g, size));
            }
            return null;
        }));
        // 清理线程：删除最老版本 + 回收（删除不得波及读取中的内容）
        futures.add(pool.submit(() -> {
            start.await();
            for (int i = 0; i < generations * 3; i++) {
                try {
                    var versions = objectStorageService.listVersions("hot.bin");
                    if (versions.size() > seedGenerations + 2) {
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
        // 回滚线程：在仍存在的种子版本之间真实回滚，回滚与按段读取同时进行
        futures.add(pool.submit(() -> {
            start.await();
            for (int i = 0; i < generations * 2; i++) {
                String target = seededVersions.get(i % seededVersions.size());
                try {
                    objectStorageService.rollback("hot.bin", target);
                    rollbackSuccess.incrementAndGet();
                } catch (StorageException ignored) {
                    // 目标版本可能正被清理线程删除，属于并发下的明确失败
                }
                Thread.sleep(1);
            }
            return null;
        }));
        // 读线程：随机范围读取，校验片段与返回元信息（totalSize/offset/length/hasMore）严格匹配
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
                        boolean metaConsistent =
                                data.totalSize() == data.version().size()
                                        && data.totalSize() == size
                                        && data.offset() == offset
                                        && slice.length == data.length()
                                        && data.length() == Math.min(length, size - offset)
                                        && data.hasMore() == (offset + data.length() < size);
                        if (!metaConsistent || !selfConsistent(slice, offset)) {
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

        assertTrue(rollbackSuccess.get() > 0, "交错期间必须真实触发回滚");
        assertEquals(0, corrupted.get(), "读取不得返回半截、拼接错位或元信息对不上的内容");
        assertTrue(readOk.get() > 0, "应当有成功的读取");
        log.info("[测试] 读/写/删/回滚/回收交错：成功={} 明确失败={} 回滚成功={} 数据损坏={}",
                readOk.get(), readFailedClean.get(), rollbackSuccess.get(), corrupted.get());
    }

    @Test
    void rangeReadWhileRollbackNeverTorn() throws Exception {
        // 两个不同长度、内容自描述的版本，反复回滚；按段读取必须始终命中其中一个完整版本
        int sizeA = 4096;
        int sizeB = 8192;
        byte[] a = pattern(101, sizeA);
        byte[] b = pattern(202, sizeB);
        String va = upload("rb-range.bin", a);
        upload("rb-range.bin", b);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger mismatches = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        int iterations = 200;

        Future<?> roller = pool.submit(() -> {
            start.await();
            for (int i = 0; i < iterations; i++) {
                // va 永远存在（只回滚不删），故回滚每次都应真实成功
                objectStorageService.rollback("rb-range.bin", va);
                rollbacks.incrementAndGet();
                // 再上传一次 b 内容，让最新版本在 a/b 之间反复切换
                upload("rb-range.bin", b);
            }
            return null;
        });
        Future<?> reader = pool.submit(() -> {
            start.await();
            Random rnd = new Random(7);
            for (int i = 0; i < iterations * 4; i++) {
                try {
                    // 先用较大的试探范围发起读取；读取返回自带 totalSize，禁止用读取之外的
                    // stat 结果推算（stat 与 read 之间版本可能切换，这正是要验证的并发点）
                    long probeOffset = rnd.nextInt(sizeA - 1);
                    long probeLength = 1 + rnd.nextInt(256);
                    var data = objectStorageService.readLatestRange("rb-range.bin",
                            probeOffset, probeLength);
                    byte[] slice;
                    try (InputStream in = data.stream()) {
                        slice = in.readAllBytes();
                    }
                    // 版本由返回的 totalSize 唯一确定（a/b 长度不同），并与 version.size 互相印证
                    long total = data.totalSize();
                    int gen = total == sizeA ? 101 : 202;
                    byte[] expect = pattern(gen, (int) total);
                    boolean ok = (total == sizeA || total == sizeB)
                            && total == data.version().size()
                            && data.offset() == probeOffset
                            && slice.length == data.length()
                            && data.length() == Math.min(probeLength, total - probeOffset)
                            && data.hasMore() == (probeOffset + data.length() < total)
                            && Arrays.equals(slice, 0, slice.length,
                                    expect, (int) probeOffset, (int) (probeOffset + slice.length));
                    if (!ok) {
                        mismatches.incrementAndGet();
                    }
                } catch (StorageException ignored) {
                    // 明确失败允许，错位数据不允许
                }
            }
            return null;
        });
        start.countDown();
        roller.get(60, TimeUnit.SECONDS);
        reader.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(iterations, rollbacks.get(), "回滚必须真实发生");
        assertEquals(0, mismatches.get(), "回滚进行中的按段读取不得拿到错位数据");
        log.info("[测试] 回滚 {} 次期间按段读取错位次数={}", rollbacks.get(), mismatches.get());
    }

    @Test
    void emptyObjectFullReadSucceedsButRangeNeverSatisfiable() throws Exception {
        upload("zero.bin", new byte[0]);

        // 空对象整包读取成功：totalSize=0、length=0、hasMore=false，流为空
        var full = objectStorageService.readLatest("zero.bin");
        assertEquals(0, full.totalSize());
        assertEquals(0, full.length());
        assertEquals(0, full.offset());
        assertFalse(full.hasMore());
        try (InputStream in = full.stream()) {
            assertEquals(-1, in.read());
        }
        // 任何范围起点都越界（含零长度）→ RANGE_NOT_SATISFIABLE
        assertRangeNotSatisfiable("zero.bin", 0, 1);
        assertRangeNotSatisfiable("zero.bin", 5, 10);
        assertRangeNotSatisfiable("zero.bin", 0, 0);
        // 负偏移仍然是写法错误 INVALID_RANGE
        assertInvalidRange("zero.bin", -1, 1);
        log.info("[测试] 空对象：整包可读且字段为零，按段返回 416，非法写法返回 400");
    }

    private static byte[] pattern(int gen, int size) {        byte[] content = new byte[size];
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
