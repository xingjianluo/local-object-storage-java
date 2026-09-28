package com.github.xingjianluo.localobjectstoragejava.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于本地磁盘的内容寻址存储实现。
 *
 * <p>布局：{@code <root>/blobs/<sha256>} 为内容文件，{@code <root>/blobs/<sha256>.ref}
 * 持久化引用计数。相同内容（相同 sha256）只保留一份数据。</p>
 *
 * <p>并发语义：所有引用计数变更按 contentId 加锁；读取时先对内容加读钉（pin），
 * 回收线程只删除引用计数为 0 且无读钉的内容，因此读取要么拿到完整文件，要么明确失败。</p>
 */
@Component
public class LocalContentStore implements ContentStore {

    private static final Logger log = LoggerFactory.getLogger(LocalContentStore.class);

    private final Path blobDir;
    private final Path tmpDir;
    /** contentId -> 引用计数（内存视图，随变更持久化到 .ref 文件） */
    private final ConcurrentHashMap<String, Integer> refCounts = new ConcurrentHashMap<>();
    /** contentId -> 活跃读取数（读钉） */
    private final ConcurrentHashMap<String, Integer> pins = new ConcurrentHashMap<>();
    /** 按 contentId 的锁 */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public LocalContentStore(StorageProperties properties) {
        Path root = Path.of(properties.getRootDir());
        this.blobDir = root.resolve("blobs");
        this.tmpDir = root.resolve("tmp");
        try {
            Files.createDirectories(blobDir);
            Files.createDirectories(tmpDir);
            loadRefCounts();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void loadRefCounts() throws IOException {
        try (var stream = Files.list(blobDir)) {
            for (Path refFile : stream.filter(p -> p.getFileName().toString().endsWith(".ref")).toList()) {
                String id = refFile.getFileName().toString();
                id = id.substring(0, id.length() - ".ref".length());
                try {
                    int count = Integer.parseInt(Files.readString(refFile).trim());
                    refCounts.put(id, count);
                } catch (NumberFormatException e) {
                    log.warn("损坏的引用计数文件 {}，按 0 处理", refFile);
                    refCounts.put(id, 0);
                }
            }
        }
        log.info("ContentStore 启动，加载 {} 条引用计数", refCounts.size());
    }

    private Object lockOf(String contentId) {
        return locks.computeIfAbsent(contentId, k -> new Object());
    }

    private Path blobPath(String contentId) {
        return blobDir.resolve(contentId);
    }

    private Path refPath(String contentId) {
        return blobDir.resolve(contentId + ".ref");
    }

    private void persistRefCount(String contentId, int count) {
        try {
            Files.writeString(refPath(contentId), Integer.toString(count));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String put(InputStream data, long size) {
        // 1. 流式写入临时文件并同时计算 sha256，内存占用与内容大小无关
        MessageDigest digest = newDigest();
        Path tmp;
        try {
            tmp = Files.createTempFile(tmpDir, "put-", ".part");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        long written = 0;
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            var out = Files.newOutputStream(tmp);
            try (out) {
                while ((n = data.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    digest.update(buf, 0, n);
                    written += n;
                }
            }
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new UncheckedIOException(e);
        }
        if (written != size) {
            deleteQuietly(tmp);
            throw new StorageException(StorageException.ErrorCode.PART_SIZE_MISMATCH,
                    "声明大小 " + size + " 与实际写入 " + written + " 不一致");
        }
        String contentId = HexFormat.of().formatHex(digest.digest());
        // 2. 原子落位：已存在则去重（丢弃临时文件），否则原子 rename
        synchronized (lockOf(contentId)) {
            Path target = blobPath(contentId);
            try {
                if (Files.exists(target)) {
                    deleteQuietly(tmp);
                    log.info("内容去重命中 contentId={}", contentId);
                } else {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                    log.info("新内容落盘 contentId={} size={}", contentId, written);
                }
            } catch (IOException e) {
                deleteQuietly(tmp);
                throw new UncheckedIOException(e);
            }
            int newCount = refCounts.merge(contentId, 1, Integer::sum);
            persistRefCount(contentId, newCount);
        }
        return contentId;
    }

    @Override
    public void retain(String contentId) {
        synchronized (lockOf(contentId)) {
            Integer count = refCounts.get(contentId);
            if (count == null || !Files.exists(blobPath(contentId))) {
                throw new StorageException(StorageException.ErrorCode.CONTENT_MISSING,
                        "内容不存在: " + contentId);
            }
            int newCount = count + 1;
            refCounts.put(contentId, newCount);
            persistRefCount(contentId, newCount);
        }
    }

    @Override
    public void release(String contentId) {
        synchronized (lockOf(contentId)) {
            Integer count = refCounts.get(contentId);
            if (count == null) {
                log.warn("release 未跟踪的内容 contentId={}，忽略", contentId);
                return;
            }
            int newCount = count - 1;
            if (newCount < 0) {
                throw new StorageException(StorageException.ErrorCode.STORAGE_CORRUPT,
                        "引用计数出现负值: " + contentId);
            }
            refCounts.put(contentId, newCount);
            persistRefCount(contentId, newCount);
        }
    }

    @Override
    public InputStream open(String contentId) {
        return openRange(contentId, 0, Long.MAX_VALUE);
    }

    @Override
    public InputStream openRange(String contentId, long offset, long length) {
        // 先加读钉再打开文件：回收线程不会删除有读钉的内容
        synchronized (lockOf(contentId)) {
            Integer count = refCounts.get(contentId);
            if (count == null || count <= 0 || !Files.exists(blobPath(contentId))) {
                throw new StorageException(StorageException.ErrorCode.CONTENT_MISSING,
                        "内容不存在或已回收: " + contentId);
            }
            pins.merge(contentId, 1, Integer::sum);
        }
        try {
            InputStream raw = Files.newInputStream(blobPath(contentId));
            // 关键：先用“全长”包装流再在其上 skip——skip 经过 {@link BoundedPinStream}
            // 会同步扣减 remaining，保证定界计数与底层文件位置一致；
            // 不能对裸流 skip（FileInputStream#skip 只推进内部 position，
            // 包装层无从感知，会导致 remaining 偏大/提前 EOF 这类错位）。
            BoundedPinStream bounded = new BoundedPinStream(raw, contentId,
                    length == Long.MAX_VALUE ? Long.MAX_VALUE : offset + length);
            long toSkip = offset;
            while (toSkip > 0) {
                long skipped = bounded.skip(toSkip);
                if (skipped <= 0) {
                    if (bounded.read() == -1) {
                        bounded.close();
                        throw new IOException("内容长度不足，无法定位到偏移 " + offset);
                    }
                    skipped = 1;
                }
                toSkip -= skipped;
            }
            // 定位完成后把可读上限重置为本次区间长度
            bounded.remaining = length;
            return bounded;
        } catch (IOException e) {
            unpin(contentId);
            throw new UncheckedIOException(e);
        }
    }

    /** 限制可读字节数的读取流，关闭时释放底层流与读钉（幂等）。 */
    private final class BoundedPinStream extends FilterInputStream {
        private final String contentId;
        private long remaining;
        private boolean closed;

        BoundedPinStream(InputStream raw, String contentId, long length) {
            super(raw);
            this.contentId = contentId;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b != -1) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                super.close();
            } finally {
                unpin(contentId);
            }
        }
    }

    private void unpin(String contentId) {
        synchronized (lockOf(contentId)) {
            pins.computeIfPresent(contentId, (k, v) -> v > 1 ? v - 1 : null);
        }
    }

    @Override
    public long sizeOf(String contentId) {
        try {
            Path p = blobPath(contentId);
            return Files.exists(p) ? Files.size(p) : -1;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Map<String, Integer> refCounts() {
        return new HashMap<>(refCounts);
    }

    @Override
    public List<String> sweep() {
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : refCounts.entrySet()) {
            String contentId = entry.getKey();
            synchronized (lockOf(contentId)) {
                Integer count = refCounts.get(contentId);
                boolean pinned = pins.getOrDefault(contentId, 0) > 0;
                if (count != null && count == 0 && !pinned) {
                    deleteQuietly(blobPath(contentId));
                    deleteQuietly(refPath(contentId));
                    refCounts.remove(contentId, 0);
                    removed.add(contentId);
                    log.info("回收不可达内容 contentId={}", contentId);
                }
            }
        }
        return removed;
    }

    @Override
    public void reconcile(Map<String, Integer> expectedRefCounts) {
        // 1. 清理写入中断遗留的临时文件（put-*.part）
        try (var stream = Files.list(tmpDir)) {
            for (Path tmp : stream.toList()) {
                deleteQuietly(tmp);
                log.info("对账：清理临时文件 {}", tmp.getFileName());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // 2. 以版本链引用为准重建引用计数；磁盘上存在但未被引用的内容按 0 引用纳入管理
        //    （崩溃可能发生在“落盘”与“计数”之间），随后由 sweep 回收
        Map<String, Integer> rebuilt = new HashMap<>(expectedRefCounts);
        try (var stream = Files.list(blobDir)) {
            for (Path blob : stream.filter(p -> !p.getFileName().toString().endsWith(".ref")).toList()) {
                String id = blob.getFileName().toString();
                if (!rebuilt.containsKey(id) && !refCounts.containsKey(id)) {
                    log.warn("对账：发现未跟踪的内容残留 {}，按 0 引用处理", id);
                }
                rebuilt.putIfAbsent(id, 0);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        refCounts.clear();
        for (Map.Entry<String, Integer> e : rebuilt.entrySet()) {
            String contentId = e.getKey();
            int count = e.getValue();
            if (!Files.exists(blobPath(contentId))) {
                // 版本链引用了内容但数据文件丢失：保留计数，读取时会以 CONTENT_MISSING 明确失败
                log.error("对账：内容 {} 被引用 {} 次但数据文件缺失", contentId, count);
            }
            refCounts.put(contentId, count);
            persistRefCount(contentId, count);
        }
        // 3. 删除已不属于任何内容的孤儿 .ref 文件
        try (var stream = Files.list(blobDir)) {
            for (Path refFile : stream.filter(p -> p.getFileName().toString().endsWith(".ref")).toList()) {
                String id = refFile.getFileName().toString();
                id = id.substring(0, id.length() - ".ref".length());
                if (!refCounts.containsKey(id)) {
                    deleteQuietly(refFile);
                    log.info("对账：清理孤儿引用计数文件 {}", refFile.getFileName());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        log.info("对账完成：跟踪 {} 个内容，其中零引用 {} 个（待回收）",
                refCounts.size(),
                refCounts.values().stream().filter(c -> c == 0).count());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("删除文件失败: {}", p, e);
        }
    }
}
