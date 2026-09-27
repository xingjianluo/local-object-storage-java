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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 范围读取测试：整包与按段边界（开头/结尾/跨分片边界/单字节）、非法范围拒绝、
 * 边读边删/边回收交错、大对象流式读取的内存约束。
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

    /** 确定性伪随机字节：byte(i) = i*31+7，无需在内存中持有大数组即可校验。 */
    static byte patternAt(long i) {
        return (byte) (i * 31 + 7);
    }

    static byte[] pattern(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = patternAt(i);
        }
        return data;
    }

    /** 通过完整上传流程写入一个对象版本（支持多分片），返回 versionId。 */
    private String upload(String objectKey, byte[]... parts) {
        List<UploadSessionManager.PartSpec> specs = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            specs.add(new UploadSessionManager.PartSpec(i + 1, parts[i].length, sha256(parts[i])));
        }
        String sessionId = sessionManager.createSession(objectKey, specs);
        for (int i = 0; i < parts.length; i++) {
            sessionManager.uploadPart(sessionId, i + 1,
                    new ByteArrayInputStream(parts[i]), parts[i].length);
        }
        String versionId = sessionManager.complete(sessionId);
        log.info("[测试] 上传完成 object={} versionId={} parts={}", objectKey, versionId, parts.length);
        return versionId;
    }

    private ObjectStorageService.RangeData readRange(String key, long start, long end) {
        return objectStorageService.readRangeLatest(key, start, end);
    }

    private byte[] readAll(InputStream in) throws Exception {
        try (in) {
            return in.readAllBytes();
        }
    }

    // ---------- 边界：整包 / 开头 / 结尾 / 单字节 ----------

    @Test
    void fullObjectViaRange() throws Exception {
        byte[] content = pattern(1000);
        upload("obj", content);

        var data = readRange("obj", 0, 999);
        assertEquals(1000, data.totalSize());
        assertEquals(0, data.rangeStart());
        assertEquals(999, data.rangeEnd());
        assertEquals(1000, data.rangeLength());
        assertFalse(data.hasMore(), "覆盖到末尾时 hasMore 应为 false");
        assertArrayEquals(content, readAll(data.stream()));
        log.info("[测试] 整包范围读取通过 totalSize={}", data.totalSize());
    }

    @Test
    void rangeAtStart() throws Exception {
        byte[] content = pattern(1000);
        upload("obj", content);

        var data = readRange("obj", 0, 9);
        assertEquals(1000, data.totalSize());
        assertEquals(0, data.rangeStart());
        assertEquals(9, data.rangeEnd());
        assertTrue(data.hasMore(), "未读到末尾时 hasMore 应为 true");
        assertArrayEquals(Arrays.copyOfRange(content, 0, 10), readAll(data.stream()));
    }

    @Test
    void rangeAtEnd() throws Exception {
        byte[] content = pattern(1000);
        upload("obj", content);

        var data = readRange("obj", 990, 999);
        assertEquals(990, data.rangeStart());
        assertEquals(999, data.rangeEnd());
        assertFalse(data.hasMore());
        assertArrayEquals(Arrays.copyOfRange(content, 990, 1000), readAll(data.stream()));
    }

    @Test
    void singleByte() throws Exception {
        byte[] content = pattern(1000);
        upload("obj", content);

        var data = readRange("obj", 500, 500);
        assertEquals(1, data.rangeLength());
        assertTrue(data.hasMore());
        assertArrayEquals(new byte[]{content[500]}, readAll(data.stream()));
    }

    @Test
    void firstAndLastByte() throws Exception {
        byte[] content = pattern(100);
        upload("obj", content);

        assertArrayEquals(new byte[]{content[0]}, readAll(readRange("obj", 0, 0).stream()));
        var last = readRange("obj", 99, 99);
        assertFalse(last.hasMore());
        assertArrayEquals(new byte[]{content[99]}, readAll(last.stream()));
    }

    @Test
    void endBeyondSizeIsTruncated() throws Exception {
        byte[] content = pattern(100);
        upload("obj", content);

        var data = readRange("obj", 90, Long.MAX_VALUE);
        assertEquals(90, data.rangeStart());
        assertEquals(99, data.rangeEnd(), "end 超出末尾应截断到最后一个字节");
        assertFalse(data.hasMore());
        assertArrayEquals(Arrays.copyOfRange(content, 90, 100), readAll(data.stream()));
    }

    // ---------- 跨分片边界 / 跨读缓冲边界 ----------

    @Test
    void rangeCrossingPartBoundaries() throws Exception {
        // 3 个分片各 100 字节，范围跨越两条接缝
        byte[] p1 = pattern(100);
        byte[] p2 = pattern(100);
        byte[] p3 = pattern(100);
        // 让三个分片内容可区分：p2/p3 用不同偏移的模式
        for (int i = 0; i < 100; i++) {
            p2[i] = patternAt(1000 + i);
            p3[i] = patternAt(2000 + i);
        }
        upload("obj", p1, p2, p3);
        byte[] all = new byte[300];
        System.arraycopy(p1, 0, all, 0, 100);
        System.arraycopy(p2, 0, all, 100, 100);
        System.arraycopy(p3, 0, all, 200, 100);

        var data = readRange("obj", 50, 250);
        assertEquals(201, data.rangeLength());
        assertArrayEquals(Arrays.copyOfRange(all, 50, 251), readAll(data.stream()));

        // 精确落在接缝上
        assertArrayEquals(Arrays.copyOfRange(all, 99, 102),
                readAll(readRange("obj", 99, 101).stream()));
        assertArrayEquals(Arrays.copyOfRange(all, 199, 202),
                readAll(readRange("obj", 199, 201).stream()));
        log.info("[测试] 跨分片边界范围读取通过");
    }

    @Test
    void rangeCrossingStreamBufferBoundary() throws Exception {
        // 对象大于 64KB 读缓冲，范围跨越 65536 边界
        byte[] content = pattern(200_000);
        upload("obj", content);

        var data = readRange("obj", 65_535, 65_537);
        assertArrayEquals(Arrays.copyOfRange(content, 65_535, 65_538), readAll(data.stream()));

        var tail = readRange("obj", 199_999, 199_999);
        assertArrayEquals(new byte[]{content[199_999]}, readAll(tail.stream()));
    }

    // ---------- 历史版本 ----------

    @Test
    void rangeReadOnHistoricalVersion() throws Exception {
        byte[] v1Content = bytes("version-one-content");
        byte[] v2Content = bytes("version-two-content-changed");
        String v1 = upload("doc", v1Content);
        upload("doc", v2Content);

        var data = objectStorageService.readRangeVersion("doc", v1, 0, 6);
        assertEquals(v1, data.version().versionId());
        assertEquals(v1Content.length, data.totalSize());
        assertArrayEquals(bytes("version"), readAll(data.stream()));

        // 最新版本不受影响的同范围内容不同
        var latest = readRange("doc", 0, 6);
        assertArrayEquals(bytes("version"), readAll(latest.stream()));
        var latestTail = readRange("doc", 20, 26);
        assertArrayEquals(Arrays.copyOfRange(v2Content, 20, 27), readAll(latestTail.stream()));
        log.info("[测试] 历史版本范围读取通过 v1={}", v1);
    }

    // ---------- 非法范围与可区分失败 ----------

    @Test
    void invalidRangesAreRejected() {
        upload("obj", pattern(100));

        var e1 = assertThrows(StorageException.class, () -> readRange("obj", -1, 10));
        assertEquals(StorageException.ErrorCode.INVALID_RANGE, e1.getCode());

        var e2 = assertThrows(StorageException.class, () -> readRange("obj", 10, 5));
        assertEquals(StorageException.ErrorCode.INVALID_RANGE, e2.getCode());

        var e3 = assertThrows(StorageException.class, () -> readRange("obj", -5, -1));
        assertEquals(StorageException.ErrorCode.INVALID_RANGE, e3.getCode());
        log.info("[测试] 非法范围拒绝通过");
    }

    @Test
    void startBeyondSizeIsNotSatisfiable() {
        upload("obj", pattern(100));

        var e1 = assertThrows(StorageException.class, () -> readRange("obj", 100, 200));
        assertEquals(StorageException.ErrorCode.RANGE_NOT_SATISFIABLE, e1.getCode());

        var e2 = assertThrows(StorageException.class, () -> readRange("obj", 10_000, 20_000));
        assertEquals(StorageException.ErrorCode.RANGE_NOT_SATISFIABLE, e2.getCode());
    }

    @Test
    void failuresAreDistinguishable() {
        String v1 = upload("obj", pattern(100));

        // 对象不存在
        var e1 = assertThrows(StorageException.class, () -> readRange("no-such", 0, 1));
        assertEquals(StorageException.ErrorCode.OBJECT_NOT_FOUND, e1.getCode());

        // 版本不存在
        var e2 = assertThrows(StorageException.class,
                () -> objectStorageService.readRangeVersion("obj", "no-such-version", 0, 1));
        assertEquals(StorageException.ErrorCode.VERSION_NOT_FOUND, e2.getCode());

        // 范围越界（与上面两种错误码不同）
        var e3 = assertThrows(StorageException.class,
                () -> objectStorageService.readRangeVersion("obj", v1, 100, 101));
        assertEquals(StorageException.ErrorCode.RANGE_NOT_SATISFIABLE, e3.getCode());

        var e4 = assertThrows(StorageException.class,
                () -> objectStorageService.readRangeVersion("obj", v1, 3, 2));
        assertEquals(StorageException.ErrorCode.INVALID_RANGE, e4.getCode());
        log.info("[测试] 失败原因可区分通过");
    }

    @Test
    void emptyObjectRangeIsNotSatisfiable() throws Exception {
        upload("empty", new byte[0]);

        // 整包读取正常，返回 0 字节
        var full = objectStorageService.readLatest("empty");
        assertEquals(0, readAll(full.stream()).length);

        var e = assertThrows(StorageException.class, () -> readRange("empty", 0, 0));
        assertEquals(StorageException.ErrorCode.RANGE_NOT_SATISFIABLE, e.getCode());
    }

    // ---------- 边读边删 / 边回收 ----------

    @Test
    void openStreamSurvivesDelete() throws Exception {
        byte[] content = pattern(10_000);
        upload("obj", content);

        // 先打开范围流（加读钉），再删除整个对象
        var data = readRange("obj", 100, 9_999);
        objectStorageService.deleteObject("obj");

        // 已打开的流仍能读出完整一致的一段
        assertArrayEquals(Arrays.copyOfRange(content, 100, 10_000), readAll(data.stream()));

        // 删除后新的读取明确失败
        var e = assertThrows(StorageException.class, () -> readRange("obj", 0, 1));
        assertEquals(StorageException.ErrorCode.OBJECT_NOT_FOUND, e.getCode());
        log.info("[测试] 边读边删：已打开的流读到完整数据，新读取明确失败");
    }

    @Test
    void openStreamSurvivesVersionDeleteAndRollback() throws Exception {
        byte[] v1Content = pattern(5_000);
        String v1 = upload("obj", v1Content);
        upload("obj", pattern(5_000));

        // 打开历史版本的范围流后删除该版本
        var data = objectStorageService.readRangeVersion("obj", v1, 0, 4_999);
        objectStorageService.deleteVersion("obj", v1);
        assertArrayEquals(v1Content, readAll(data.stream()));

        // 回滚期间并发读取：回滚生成新版本，旧版本读取不受影响
        String v2 = objectStorageService.listVersions("obj").get(0).versionId();
        var duringRollback = objectStorageService.readRangeVersion("obj", v2, 0, 100);
        String v3 = objectStorageService.rollback("obj", v2);
        assertArrayEquals(Arrays.copyOfRange(pattern(5_000), 0, 101),
                readAll(duringRollback.stream()));
        assertEquals(v3, objectStorageService.listVersions("obj").get(0).versionId());
        log.info("[测试] 边读边删版本/回滚通过");
    }

    @Test
    void openStreamSurvivesGarbageCollection() throws Exception {
        byte[] content = pattern(10_000);
        upload("obj", content);
        String contentId = objectStorageService.listVersions("obj").get(0).contentId();

        // 打开范围流（加读钉）→ 删除对象（引用归零）→ 回收：有读钉不能删
        var data = readRange("obj", 0, 9_999);
        objectStorageService.deleteObject("obj");
        assertTrue(contentStore.sweep().isEmpty(), "有读钉的内容不应被回收");
        assertTrue(Files.exists(tempDir.resolve("blobs").resolve(contentId)));

        // 流内数据完整一致
        assertArrayEquals(content, readAll(data.stream()));

        // 读钉释放后再回收，残留被清掉
        assertEquals(List.of(contentId), contentStore.sweep());
        assertFalse(Files.exists(tempDir.resolve("blobs").resolve(contentId)));
        log.info("[测试] 边读边回收：读钉保护期间不被回收，释放后可回收");
    }

    @Test
    void rangeReadNeverReturnsPartialOrShiftedContent() throws Exception {
        // 交错场景：多次范围读取与删除/回收交错，结果要么完整一致要么明确失败
        byte[] content = pattern(50_000);
        upload("obj", content);

        List<InputStream> openStreams = new ArrayList<>();
        List<long[]> ranges = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            long start = i * 5_000L;
            long end = start + 4_999;
            openStreams.add(readRange("obj", start, end).stream());
            ranges.add(new long[]{start, end});
        }
        objectStorageService.deleteObject("obj");
        contentStore.sweep();

        for (int i = 0; i < openStreams.size(); i++) {
            long start = ranges.get(i)[0];
            long end = ranges.get(i)[1];
            byte[] expected = Arrays.copyOfRange(content, (int) start, (int) end + 1);
            assertArrayEquals(expected, readAll(openStreams.get(i)),
                    "交错回收下第 " + i + " 段必须完整一致");
        }
        log.info("[测试] 交错读取/删除/回收：{} 段全部完整一致", openStreams.size());
    }

    // ---------- 大对象流式读取的内存约束 ----------

    /** 按需生成确定性字节的流，避免测试本身在堆上持有大数组。 */
    private static final class PatternInputStream extends InputStream {
        private final long size;
        private long pos;

        PatternInputStream(long size) {
            this.size = size;
        }

        @Override
        public int read() {
            if (pos >= size) {
                return -1;
            }
            return patternAt(pos++) & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= size) {
                return -1;
            }
            int n = (int) Math.min(len, size - pos);
            for (int i = 0; i < n; i++) {
                b[off + i] = patternAt(pos + i);
            }
            pos += n;
            return n;
        }
    }

    @Test
    void largeObjectReadsUseConstantMemory() throws Exception {
        long size = 96L * 1024 * 1024; // 96MB
        properties.setMaxPartSize(128L * 1024 * 1024);
        // 先流式算 sha256，再流式上传，全程不在堆上持有对象内容
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = new PatternInputStream(size)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        String sha = HexFormat.of().formatHex(digest.digest());
        var spec = new UploadSessionManager.PartSpec(1, size, sha);
        String sessionId = sessionManager.createSession("big", List.of(spec));
        sessionManager.uploadPart(sessionId, 1, new PatternInputStream(size), size);
        sessionManager.complete(sessionId);
        log.info("[测试] 大对象上传完成 size={}", size);

        System.gc();
        long heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        // 连续读取多个大段（合计超过对象一半），逐块校验内容
        long[][] ranges = {
                {0, 32L * 1024 * 1024 - 1},
                {16L * 1024 * 1024, 80L * 1024 * 1024 - 1},
                {size - 1024, size - 1},
        };
        long totalRead = 0;
        for (long[] r : ranges) {
            var data = readRange("big", r[0], r[1]);
            try (var in = data.stream()) {
                byte[] buf = new byte[64 * 1024];
                long pos = r[0];
                int n;
                while ((n = in.read(buf)) != -1) {
                    for (int i = 0; i < n; i++) {
                        assertEquals(patternAt(pos + i), buf[i],
                                "大对象范围读取内容错位 offset=" + (pos + i));
                    }
                    pos += n;
                    totalRead += n;
                }
                assertEquals(r[1] + 1, pos, "实际读到的字节数必须精确等于区间长度");
            }
        }
        System.gc();
        long heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long growth = heapAfter - heapBefore;
        log.info("[测试] 大对象连续读取 {} 字节，堆增长 {} 字节", totalRead, growth);
        assertTrue(totalRead > size / 2);
        assertTrue(growth < 32L * 1024 * 1024,
                "堆增长应控制在小固定范围内，实际增长 " + growth);
    }
}
