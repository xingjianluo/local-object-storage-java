package com.github.xingjianluo.localobjectstoragejava.store;

import com.github.xingjianluo.localobjectstoragejava.config.StorageProperties;
import com.github.xingjianluo.localobjectstoragejava.model.ObjectMeta;
import com.github.xingjianluo.localobjectstoragejava.model.ObjectVersion;
import com.github.xingjianluo.localobjectstoragejava.model.StorageException;
import com.github.xingjianluo.localobjectstoragejava.model.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内容块（blob）存储：按内容 SHA-256 寻址，同内容只保留一份物理数据。
 *
 * <p>布局：
 * <ul>
 *   <li>{@code <root>/blobs/xx/<contentId>}：不可变内容文件</li>
 *   <li>{@code <root>/blobs/xx/<contentId>.refs/<versionKey-versionId>}：引用关系标记文件</li>
 *   <li>{@code <root>/blobs/tmp/}：合并临时文件，崩溃后可安全清理</li>
 * </ul>
 *
 * <p>一致性规则：</p>
 * <ol>
 *   <li>合并全程流式（固定大小缓冲 + DigestOutputStream），内存占用不随对象大小增长；</li>
 *   <li>引用计数以 refs 目录下的标记文件为准（可持久化、可重建），内存只做缓存；</li>
 *   <li>所有 blob 状态变更串行于一把全局锁；与对象元数据锁的顺序固定为
 *       “对象 key 锁 -> blob 锁”，避免死锁；</li>
 *   <li>删除 blob 前要求活动读者数为 0，回收期间新读者要么拿到完整文件、要么得到
 *       OBJECT_NOT_FOUND/VERSION_DELETED，绝不返回半成品。</li>
 * </ol>
 */
@Component
public class BlobStore {

    private static final Logger log = LoggerFactory.getLogger(BlobStore.class);

    /** 一份合并结果：内容寻址 id、字节数、校验值。 */
    public static final class StoredContent {
        private final String contentId;
        private final long size;
        private final String checksum;

        public StoredContent(String contentId, long size, String checksum) {
            this.contentId = contentId;
            this.size = size;
            this.checksum = checksum;
        }

        public String getContentId() { return contentId; }
        public long getSize() { return size; }
        public String getChecksum() { return checksum; }
    }

    private final Path blobsDir;
    private final Path tmpDir;
    private final int copyBufferSize;
    private final long gcGraceMillis;

    /** blob 全局状态锁。 */
    private final Object blobLock = new Object();
    /** contentId -> 正在读取该内容的活动读者数。 */
    private final Map<String, Integer> activeReaders = new ConcurrentHashMap<>();
    /** contentId -> 首次发现 refcount=0 的时间戳（用于宽限期）。 */
    private final Map<String, Long> unreachableSince = new HashMap<>();

    public BlobStore(StorageProperties properties) {
        this.blobsDir = Path.of(properties.getRootDir(), "blobs");
        this.tmpDir = blobsDir.resolve("tmp");
        this.copyBufferSize = properties.getCopyBufferSize();
        this.gcGraceMillis = properties.getBlobGcGraceMillis();
        try {
            Files.createDirectories(tmpDir);
        } catch (IOException e) {
            throw new UncheckedIOException("创建 blob 目录失败: " + blobsDir, e);
        }
        cleanupStaleTempFiles();
    }

    private void cleanupStaleTempFiles() {
        try (var temps = Files.list(tmpDir)) {
            temps.forEach(t -> {
                try {
                    Files.deleteIfExists(t);
                } catch (IOException e) {
                    log.warn("清理旧临时文件失败 {}", t, e);
                }
            });
        } catch (IOException e) {
            log.warn("扫描临时目录失败 {}", tmpDir, e);
        }
    }

    private Path shardDir(String contentId) {
        return blobsDir.resolve(contentId.substring(0, 2));
    }

    public Path blobPath(String contentId) {
        return shardDir(contentId).resolve(contentId);
    }

    private Path refsDir(String contentId) {
        return shardDir(contentId).resolve(contentId + ".refs");
    }

    private Path refMarker(String contentId, String objectKey, String versionId) {
        return refsDir(contentId).resolve(MetadataStore.encodeKey(objectKey) + "__" + versionId);
    }

    // ------------------------------------------------------------------
    // 合并与去重
    // ------------------------------------------------------------------

    /**
     * 按编号顺序流式合并分片到临时文件，边拷贝边计算 SHA-256，
     * 校验通过后原子发布为内容寻址 blob（若已存在则直接复用）。
     *
     * @param partFiles        分片编号 -> 分片文件，必须包含 1..totalParts 全部编号
     * @param expectedSize     期望总字节数，&lt;0 表示不校验大小
     * @param expectedChecksum 期望整体 SHA-256（十六进制），null 表示不校验
     */
    public StoredContent mergeAndPublish(Map<Integer, Path> partFiles, int totalParts,
                                         long expectedSize, String expectedChecksum) {
        for (int i = 1; i <= totalParts; i++) {
            if (!partFiles.containsKey(i)) {
                throw new StorageException(ErrorCode.PART_MISSING, "缺少分片: " + i);
            }
        }
        Path temp;
        String actualChecksum;
        long actualSize;
        try {
            temp = Files.createTempFile(tmpDir, "merge-", ".part");
        } catch (IOException e) {
            throw new UncheckedIOException("创建合并临时文件失败", e);
        }
        try {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 不可用", e);
            }
            long size = 0;
            try (OutputStream out = Files.newOutputStream(temp);
                 DigestOutputStream digestOut = new DigestOutputStream(out, digest)) {
                byte[] buffer = new byte[copyBufferSize];
                for (int i = 1; i <= totalParts; i++) {
                    try (InputStream in = new BufferedInputStream(Files.newInputStream(partFiles.get(i)))) {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            digestOut.write(buffer, 0, read);
                            size += read;
                        }
                    }
                }
                digestOut.flush();
            }
            actualChecksum = HexFormat.of().formatHex(digest.digest());
            actualSize = size;
            if (expectedSize >= 0 && expectedSize != actualSize) {
                throw new StorageException(ErrorCode.TOTAL_CHECKSUM_MISMATCH,
                        String.format("合并后大小不符: expected=%d actual=%d", expectedSize, actualSize));
            }
            if (expectedChecksum != null && !expectedChecksum.equalsIgnoreCase(actualChecksum)) {
                throw new StorageException(ErrorCode.TOTAL_CHECKSUM_MISMATCH,
                        String.format("合并后校验失败: expected=%s actual=%s", expectedChecksum, actualChecksum));
            }
            return publish(temp, actualChecksum, actualSize);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new UncheckedIOException("合并分片失败", e);
        } catch (RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
    }

    /**
     * 临时文件转正：内容已存在则丢弃临时文件复用现有 blob，否则原子 rename 发布。
     * rename 在同目录下完成；并发同内容时 ATOMIC_MOVE 到同一目标只有一个成功，
     * 失败者删除临时文件后复用已发布 blob。
     */
    private StoredContent publish(Path temp, String contentId, long size) throws IOException {
        Path target = blobPath(contentId);
        synchronized (blobLock) {
            Files.createDirectories(shardDir(contentId));
            if (Files.isRegularFile(target)) {
                Files.deleteIfExists(temp);
                log.info("内容去重命中 contentId={} size={} 复用已有 blob", contentId, size);
                return new StoredContent(contentId, size, contentId);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFail) {
                if (Files.isRegularFile(target)) {
                    Files.deleteIfExists(temp);
                    log.info("并发合并后去重命中 contentId={}", contentId);
                    return new StoredContent(contentId, size, contentId);
                }
                throw atomicFail;
            }
            Files.createDirectories(refsDir(contentId));
            log.info("发布新内容 contentId={} size={}", contentId, size);
            return new StoredContent(contentId, size, contentId);
        }
    }

    // ------------------------------------------------------------------
    // 引用关系
    // ------------------------------------------------------------------

    /**
     * 增加一条版本引用。重复增加（同 key+versionId）幂等。
     */
    public void acquireRef(String contentId, String objectKey, String versionId) {
        synchronized (blobLock) {
            try {
                Files.createDirectories(refsDir(contentId));
                Path marker = refMarker(contentId, objectKey, versionId);
                if (Files.exists(marker)) {
                    return;
                }
                Path tmp = refsDir(contentId).resolve(marker.getFileName() + ".tmp");
                Files.writeString(tmp, objectKey + "@" + versionId);
                Files.move(tmp, marker, StandardCopyOption.ATOMIC_MOVE);
                unreachableSince.remove(contentId);
                log.debug("acquireRef contentId={} ref={}/{}", contentId, objectKey, versionId);
            } catch (IOException e) {
                throw new UncheckedIOException("增加内容引用失败: " + contentId, e);
            }
        }
    }

    /**
     * 释放一条版本引用。
     */
    public void releaseRef(String contentId, String objectKey, String versionId) {
        synchronized (blobLock) {
            try {
                Files.deleteIfExists(refMarker(contentId, objectKey, versionId));
                if (countRefs(contentId) == 0) {
                    unreachableSince.putIfAbsent(contentId, System.currentTimeMillis());
                }
                log.debug("releaseRef contentId={} ref={}/{}", contentId, objectKey, versionId);
            } catch (IOException e) {
                throw new UncheckedIOException("释放内容引用失败: " + contentId, e);
            }
        }
    }

    /**
     * 统计 refs 目录中的标记文件数（引用计数的唯一事实来源）。
     */
    public int countRefs(String contentId) {
        Path dir = refsDir(contentId);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var entries = Files.list(dir)) {
            return (int) entries.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                    .count();
        } catch (IOException e) {
            throw new UncheckedIOException("统计引用失败: " + contentId, e);
        }
    }

    // ------------------------------------------------------------------
    // 读取（活动读者保护）
    // ------------------------------------------------------------------

    /**
     * 打开一个内容读通道。调用方必须关闭返回的通道。
     * 若内容已不存在（被回收），明确抛出异常，绝不返回残缺数据。
     */
    public ReadableByteChannel openChannel(String contentId) {
        beginRead(contentId);
        boolean success = false;
        try {
            ReadableByteChannel channel = Files.newByteChannel(blobPath(contentId));
            success = true;
            return new GuardedChannel(channel, contentId, this);
        } catch (IOException e) {
            throw new StorageException(ErrorCode.OBJECT_NOT_FOUND, "内容不可读: " + contentId);
        } finally {
            if (!success) {
                endRead(contentId);
            }
        }
    }

    public InputStream openStream(String contentId) {
        return Channels.newInputStream(openChannel(contentId));
    }

    private void beginRead(String contentId) {
        synchronized (blobLock) {
            if (!Files.isRegularFile(blobPath(contentId))) {
                throw new StorageException(ErrorCode.OBJECT_NOT_FOUND, "内容不存在或已被回收: " + contentId);
            }
            activeReaders.merge(contentId, 1, Integer::sum);
        }
    }

    private void endRead(String contentId) {
        synchronized (blobLock) {
            int remaining = activeReaders.merge(contentId, -1, Integer::sum);
            if (remaining <= 0) {
                activeReaders.remove(contentId);
                blobLock.notifyAll();
            }
        }
    }

    /** 关闭时自动解除活动读者登记的通道包装。 */
    private static final class GuardedChannel implements ReadableByteChannel {
        private final ReadableByteChannel delegate;
        private final String contentId;
        private final BlobStore owner;
        private boolean closed;

        GuardedChannel(ReadableByteChannel delegate, String contentId, BlobStore owner) {
            this.delegate = delegate;
            this.contentId = contentId;
            this.owner = owner;
        }

        @Override
        public int read(java.nio.ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                try {
                    delegate.close();
                } finally {
                    owner.endRead(contentId);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 回收
    // ------------------------------------------------------------------

    /**
     * 以当前对象元数据为权威重建引用关系，并返回不可达（零引用）内容。
     * 用于对账与回收。
     */
    public Set<String> reconcileAndFindUnreachable(List<ObjectMeta> metas) {
        synchronized (blobLock) {
            Set<String> reachable = new HashSet<>();
            Map<String, List<String>> expectedRefs = new HashMap<>();
            for (ObjectMeta meta : metas) {
                for (ObjectVersion v : meta.getVersions()) {
                    if (v.isDeleted()) {
                        continue;
                    }
                    reachable.add(v.getContentId());
                    expectedRefs.computeIfAbsent(v.getContentId(), k -> new ArrayList<>())
                            .add(meta.getKey() + "@" + v.getVersionId());
                }
            }
            Set<String> onDisk = listContentIds();
            Set<String> unreachable = new HashSet<>();
            for (String id : onDisk) {
                if (!reachable.contains(id)) {
                    unreachable.add(id);
                }
            }
            return unreachable;
        }
    }

    /**
     * 删除零引用且超过宽限期、且没有活动读者的 blob 及其 refs 目录。
     * 存在活动读者时等待到读者退出（由定时任务周期触发，单次回收做有限等待）。
     *
     * @return 实际删除的 blob 数
     */
    public int deleteUnreachable(Set<String> candidates, long nowMillis, long waitMillis) {
        synchronized (blobLock) {
            int deleted = 0;
            long deadline = nowMillis + waitMillis;
            for (String contentId : candidates) {
                if (countRefs(contentId) > 0) {
                    unreachableSince.remove(contentId);
                    continue;
                }
                long since = unreachableSince.computeIfAbsent(contentId, k -> nowMillis);
                if (nowMillis - since < gcGraceMillis) {
                    continue;
                }
                while (activeReaders.containsKey(contentId) && System.currentTimeMillis() < deadline) {
                    try {
                        blobLock.wait(Math.max(1, deadline - System.currentTimeMillis()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.info("回收等待活动读者被中断 contentId={}", contentId);
                        return deleted;
                    }
                }
                if (activeReaders.containsKey(contentId)) {
                    log.info("仍有活动读者，本次跳过回收 contentId={}", contentId);
                    continue;
                }
                if (deleteBlobFiles(contentId)) {
                    deleted++;
                    unreachableSince.remove(contentId);
                }
            }
            return deleted;
        }
    }

    private boolean deleteBlobFiles(String contentId) {
        try {
            Path refs = refsDir(contentId);
            if (Files.exists(refs)) {
                try (var entries = Files.walk(refs)) {
                    entries.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
                }
            }
            boolean removed = Files.deleteIfExists(blobPath(contentId));
            if (removed) {
                log.info("回收不可达内容 contentId={}", contentId);
            }
            return removed;
        } catch (IOException e) {
            log.warn("删除 blob 失败 contentId={}", contentId, e);
            return false;
        }
    }

    /**
     * 扫描磁盘上的全部内容 id（blob 文件，不含 refs 与 tmp）。
     */
    public Set<String> listContentIds() {
        Set<String> ids = new HashSet<>();
        if (!Files.isDirectory(blobsDir)) {
            return ids;
        }
        try (var shards = Files.list(blobsDir)) {
            shards.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().equals("tmp"))
                    .forEach(shard -> {
                        try (var entries = Files.list(shard)) {
                            entries.filter(Files::isRegularFile).forEach(f ->
                                    ids.add(f.getFileName().toString()));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("列举 blob 失败", e);
        }
        return ids;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除文件失败 {}", path, e);
        }
    }
}
