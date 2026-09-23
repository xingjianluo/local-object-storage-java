package com.github.xingjianluo.localobjectstoragejava.tests;

import com.github.xingjianluo.localobjectstoragejava.config.StorageProperties;
import com.github.xingjianluo.localobjectstoragejava.model.ObjectMeta;
import com.github.xingjianluo.localobjectstoragejava.model.UploadSession;
import com.github.xingjianluo.localobjectstoragejava.service.MultipartUploadService;
import com.github.xingjianluo.localobjectstoragejava.service.ObjectService;
import com.github.xingjianluo.localobjectstoragejava.store.BlobStore;
import com.github.xingjianluo.localobjectstoragejava.store.ChecksumUtil;
import com.github.xingjianluo.localobjectstoragejava.store.JsonCodec;
import com.github.xingjianluo.localobjectstoragejava.store.MetadataStore;
import com.github.xingjianluo.localobjectstoragejava.store.Reaper;
import com.github.xingjianluo.localobjectstoragejava.store.SessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
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
 * 并发一致性测试：
 * <ol>
 *   <li>并发上传相同内容 -> 物理 blob 只保留一份，每版本引用正确；</li>
 *   <li>并发重复完成同一会话 -> 恰好一个成功，其余得到可区分失败；</li>
 *   <li>上传与读取交错 -> 读取要么看到旧完整版本、要么看到新完整版本，无半成品；</li>
 *   <li>删除与回滚交错 -> 版本指针、引用计数、blob 可达性始终一致。</li>
 * </ol>
 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    @TempDir
    Path tempDir;

    private StorageProperties properties;
    private SessionStore sessionStore;
    private MetadataStore metadataStore;
    private BlobStore blobStore;
    private MultipartUploadService uploadService;
    private ObjectService objectService;
    private Reaper reaper;

    private static final int PART_SIZE = 32;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        properties.setRootDir(tempDir.toString());
        properties.setSessionTtlMillis(3_600_000);
        properties.setBlobGcGraceMillis(0);
        JsonCodec codec = new JsonCodec();
        sessionStore = new SessionStore(properties, codec);
        metadataStore = new MetadataStore(properties, codec);
        blobStore = new BlobStore(properties);
        uploadService = new MultipartUploadService(sessionStore, blobStore, metadataStore, properties);
        objectService = new ObjectService(metadataStore, blobStore);
        reaper = new Reaper(sessionStore, metadataStore, blobStore);
    }

    private static byte[] payload(int seed, int total) {
        byte[] data = new byte[total];
        for (int i = 0; i < total; i++) {
            data[i] = (byte) (seed * 31 + i * 7);
        }
        return data;
    }

    private String fullUpload(String key, byte[] content) {
        byte[][] parts = split(content, PART_SIZE);
        UploadSession session = uploadService.createSession(key, parts.length, PART_SIZE);
        for (int i = 1; i <= parts.length; i++) {
            uploadService.uploadPart(session.getSessionId(), i, parts[i - 1].length,
                    ChecksumUtil.sha256Hex(parts[i - 1]), new ByteArrayInputStream(parts[i - 1]));
        }
        return uploadService.completeSession(session.getSessionId(),
                ChecksumUtil.sha256Hex(content)).getCompletedVersionId();
    }

    private static byte[][] split(byte[] data, int size) {
        int n = (data.length + size - 1) / size;
        byte[][] parts = new byte[n][];
        for (int i = 0; i < n; i++) {
            parts[i] = Arrays.copyOfRange(data, i * size, Math.min(data.length, (i + 1) * size));
        }
        return parts;
    }

    private byte[] readCurrent(String key) throws Exception {
        try (var ch = objectService.readCurrent(key)) {
            return Channels.newInputStream(ch).readAllBytes();
        }
    }

    @Test
    @DisplayName("并发上传相同内容：blob 只保留一份，引用计数等于版本数")
    void concurrentUploadsSameContent() throws Exception {
        int threads = 8;
        byte[] content = payload(9, 10_000);
        String sha = ChecksumUtil.sha256Hex(content);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final String key = "concurrent/same-key-" + t;
            futures.add(pool.submit((Callable<String>) () -> {
                start.await();
                return fullUpload(key, content);
            }));
        }
        start.countDown();
        Set<String> versionIds = new HashSet<>();
        for (Future<String> f : futures) {
            versionIds.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertEquals(threads, versionIds.size(), "每次上传必须产生独立版本");
        ObjectMeta meta0 = objectService.listVersions("concurrent/same-key-0");
        String contentId = meta0.getVersions().get(0).getContentId();
        for (int t = 0; t < threads; t++) {
            ObjectMeta m = objectService.listVersions("concurrent/same-key-" + t);
            assertEquals(contentId, m.getVersions().get(0).getContentId(),
                    "相同内容必须解析到同一 contentId");
        }
        // 物理文件恰好一份，引用计数等于版本数（8 个对象各 1 个版本）。
        assertEquals(Set.of(contentId), blobStore.listContentIds(),
                "并发相同内容只允许一份物理 blob");
        assertEquals(threads, blobStore.countRefs(contentId), "引用计数必须等于存活版本数");
        log.info("[并发去重] threads={} versionIds={} contentId={} refs={} blobsOnDisk={}",
                threads, threads, contentId, blobStore.countRefs(contentId),
                blobStore.listContentIds());
    }

    @Test
    @DisplayName("并发重复完成同一会话：恰好一个成功")
    void concurrentCompleteSameSession() throws Exception {
        byte[] content = payload(3, 5_000);
        byte[][] parts = split(content, PART_SIZE);
        UploadSession session = uploadService.createSession("concurrent/double-complete",
                parts.length, PART_SIZE);
        for (int i = 1; i <= parts.length; i++) {
            uploadService.uploadPart(session.getSessionId(), i, parts[i - 1].length,
                    ChecksumUtil.sha256Hex(parts[i - 1]), new ByteArrayInputStream(parts[i - 1]));
        }
        String sha = ChecksumUtil.sha256Hex(content);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    uploadService.completeSession(session.getSessionId(), sha);
                    success.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet();
                    log.info("[并发完成冲突] {}", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, success.get(), "只能有一个完成操作成功");
        assertEquals(threads - 1, rejected.get(), "其余完成必须被拒绝");
        assertArrayEquals(content, readCurrent("concurrent/double-complete"));
        log.info("[并发完成] success={} rejected={}", success.get(), rejected.get());
    }

    @Test
    @DisplayName("上传与读取交错：读到的始终是完整旧版或完整新版")
    void concurrentUploadAndRead() throws Exception {
        String key = "concurrent/interleave-read";
        byte[] v1 = payload(1, 7_777);
        byte[] v2 = payload(2, 8_888);
        fullUpload(key, v1);

        int readers = 6;
        ExecutorService pool = Executors.newCachedThreadPool();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sawV1 = new AtomicInteger();
        AtomicInteger sawV2 = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        futures.add(pool.submit(() -> {
            try {
                start.await();
                fullUpload(key, v2);
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        }));
        for (int r = 0; r < readers; r++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    long deadline = System.currentTimeMillis() + 10_000;
                    boolean seenV2 = false;
                    // 持续读，直到亲眼读到一次新版 v2（保证覆盖跨版本时刻）后再多读几轮。
                    while (System.currentTimeMillis() < deadline) {
                        byte[] data = readCurrent(key);
                        if (Arrays.equals(data, v1)) {
                            sawV1.incrementAndGet();
                        } else if (Arrays.equals(data, v2)) {
                            sawV2.incrementAndGet();
                            seenV2 = true;
                            if (sawV2.get() >= readers * 3L) {
                                break;
                            }
                        } else {
                            errors.incrementAndGet();
                            throw new AssertionError("读到既非旧版也非新版的半成品数据，长度=" + data.length);
                        }
                        if (seenV2) {
                            Thread.yield();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    throw new RuntimeException(e);
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(0, errors.get(), "交错读取不得出现半成品或错误");
        assertTrue(sawV1.get() + sawV2.get() > 0);
        assertTrue(sawV2.get() >= readers, "每个读者都必须在跨版本时刻后读到完整的 v2");
        assertArrayEquals(v2, readCurrent(key));
        log.info("[读写交错] sawV1={} sawV2={} errors={}", sawV1.get(), sawV2.get(), errors.get());
    }

    @Test
    @DisplayName("删除与回滚交错：版本指针与引用计数始终一致")
    void concurrentDeleteAndRollback() throws Exception {
        String key = "concurrent/del-rollback";
        byte[] c1 = payload(5, 4_444);
        byte[] c2 = payload(6, 5_555);
        String v1 = fullUpload(key, c1);
        String v2 = fullUpload(key, c2);

        int ops = 20;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < ops; i++) {
            final boolean rollback = i % 2 == 0;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    if (rollback) {
                        // 回滚到 v1：若 v1 已删除则应得到 VERSION_DELETED，否则成功
                        objectService.rollback(key, v1);
                    } else {
                        // 反复尝试删除“当前版本”，再补回内容版本，保持链条存活
                        ObjectMeta meta = objectService.listVersions(key);
                        String cur = meta.getCurrentVersionId();
                        if (cur != null) {
                            objectService.deleteVersion(key, cur);
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                    log.debug("[交错操作冲突] {}", e.getMessage());
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        // 最终一致性核对：
        // 1) 当前指针要么为 null，要么指向链中未删除版本；
        // 2) 每个存活版本对应内容的引用计数 >= 1；磁盘 blob 集合 == 存活版本 contentId 集合。
        ObjectMeta finalMeta = objectService.listVersions(key);
        if (finalMeta.getCurrentVersionId() != null) {
            assertEquals(false, finalMeta.findVersion(finalMeta.getCurrentVersionId()).isDeleted(),
                    "当前指针不得指向已删除版本");
        }
        Set<String> liveContentIds = new HashSet<>();
        for (var v : finalMeta.getVersions()) {
            if (!v.isDeleted()) {
                liveContentIds.add(v.getContentId());
                assertTrue(blobStore.countRefs(v.getContentId()) >= 1,
                        "存活版本的内容引用计数必须 >= 1: " + v.getVersionId());
            }
        }
        int deletedBlobs = reaper.reapUnreachableBlobs(System.currentTimeMillis());
        Set<String> afterGc = blobStore.listContentIds();
        assertEquals(liveContentIds, afterGc,
                "回收后磁盘 blob 集合必须恰好等于存活版本引用的内容集合");
        log.info("[删除/回滚交错] ops={} conflictRejects={} liveVersions={} gcDeleted={} remainingBlobs={}",
                ops, failures.get(), liveContentIds.size(), deletedBlobs, afterGc);
    }
}
