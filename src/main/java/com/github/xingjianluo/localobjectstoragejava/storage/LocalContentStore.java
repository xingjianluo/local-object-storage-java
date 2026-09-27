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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            return new FilterInputStream(raw) {
                @Override
                public void close() throws IOException {
                    super.close();
                    unpin(contentId);
                }
            };
        } catch (IOException e) {
            unpin(contentId);
            throw new UncheckedIOException(e);
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
    public ReconcileReport reconcile(Map<String, Integer> expectedRefs) {
        List<String> corrected = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        // 1. 以版本链元数据为准，修正引用计数漂移（崩溃可能发生在 retain/release 与落盘之间）
        for (Map.Entry<String, Integer> e : expectedRefs.entrySet()) {
            String contentId = e.getKey();
            int expected = e.getValue();
            synchronized (lockOf(contentId)) {
                if (!Files.exists(blobPath(contentId))) {
                    missing.add(contentId);
                    log.error("对账发现元数据引用的内容缺失 contentId={} 应有引用={}",
                            contentId, expected);
                    continue;
                }
                Integer current = refCounts.get(contentId);
                if (current == null || current != expected) {
                    refCounts.put(contentId, expected);
                    persistRefCount(contentId, expected);
                    corrected.add(contentId);
                    log.info("对账修正引用计数 contentId={} {} -> {}", contentId, current, expected);
                }
            }
        }
        // 2. 删除不被任何版本引用的残留（崩溃遗留在 put 之后、版本提交之前的内容等）
        Set<String> orphans = new LinkedHashSet<>();
        try (var stream = Files.list(blobDir)) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString();
                String contentId = name.endsWith(".ref")
                        ? name.substring(0, name.length() - ".ref".length()) : name;
                if (expectedRefs.containsKey(contentId)) {
                    continue;
                }
                synchronized (lockOf(contentId)) {
                    boolean pinned = pins.getOrDefault(contentId, 0) > 0;
                    if (!expectedRefs.containsKey(contentId) && !pinned) {
                        deleteQuietly(blobPath(contentId));
                        deleteQuietly(refPath(contentId));
                        refCounts.remove(contentId);
                        orphans.add(contentId);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // 3. 清理写入中断遗留的临时文件
        int removedTmp = 0;
        try (var stream = Files.list(tmpDir)) {
            for (Path p : stream.toList()) {
                deleteQuietly(p);
                removedTmp++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> removedOrphans = new ArrayList<>(orphans);
        log.info("对账完成：修正引用 {} 个，删除残留 {} 个，缺失 {} 个，清理临时文件 {} 个",
                corrected.size(), removedOrphans.size(), missing.size(), removedTmp);
        return new ReconcileReport(removedOrphans, missing, corrected, removedTmp);
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
