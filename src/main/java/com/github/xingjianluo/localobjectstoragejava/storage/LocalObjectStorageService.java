package com.github.xingjianluo.localobjectstoragejava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于本地磁盘的版本化对象存储实现。
 *
 * <p>布局：{@code <root>/objects/<base64url(key)>/meta.json} 保存版本链元数据，
 * 实际数据由 {@link ContentStore} 按内容寻址保存。</p>
 *
 * <p>一致性规则：每个对象一把锁；新增版本前先 retain 内容再落元数据，
 * 删除版本时先落元数据再 release 内容，保证“元数据可见 ⇒ 内容必可达”。</p>
 */
@Component
public class LocalObjectStorageService implements ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalObjectStorageService.class);

    /** 版本链元数据（持久化到 meta.json 的结构） */
    static class ObjectMeta {
        public String objectKey;
        public List<VersionEntry> versions = new ArrayList<>();
    }

    static class VersionEntry {
        public String versionId;
        public String contentId;
        public long size;
        public long createdAt;

        VersionEntry() {
        }

        VersionEntry(String versionId, String contentId, long size, long createdAt) {
            this.versionId = versionId;
            this.contentId = contentId;
            this.size = size;
            this.createdAt = createdAt;
        }
    }

    private final ContentStore contentStore;
    private final Path objectsDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ObjectMeta> metas = new ConcurrentHashMap<>();

    public LocalObjectStorageService(StorageProperties properties, ContentStore contentStore) {
        this.contentStore = contentStore;
        this.objectsDir = Path.of(properties.getRootDir()).resolve("objects");
        try {
            Files.createDirectories(objectsDir);
            loadAll();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void loadAll() throws IOException {
        try (var stream = Files.list(objectsDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                Path metaFile = dir.resolve("meta.json");
                if (Files.exists(metaFile)) {
                    ObjectMeta meta = mapper.readValue(metaFile.toFile(), ObjectMeta.class);
                    metas.put(meta.objectKey, meta);
                }
            }
        }
        log.info("ObjectStorage 启动，加载 {} 个对象的版本链", metas.size());
    }

    private Object lockOf(String objectKey) {
        return keyLocks.computeIfAbsent(objectKey, k -> new Object());
    }

    private Path metaPath(String objectKey) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectKey.getBytes(StandardCharsets.UTF_8));
        return objectsDir.resolve(encoded).resolve("meta.json");
    }

    /** 在对象锁内调用：原子写元数据（临时文件 + rename）。 */
    private void persistMeta(ObjectMeta meta) {
        Path metaFile = metaPath(meta.objectKey);
        try {
            Files.createDirectories(metaFile.getParent());
            Path tmp = metaFile.resolveSibling("meta.json.tmp");
            mapper.writeValue(tmp.toFile(), meta);
            Files.move(tmp, metaFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void removeMetaFile(String objectKey) {
        Path metaFile = metaPath(objectKey);
        try {
            Files.deleteIfExists(metaFile);
            Files.deleteIfExists(metaFile.getParent());
        } catch (IOException e) {
            log.warn("删除对象元数据失败: {}", objectKey, e);
        }
    }

    private static VersionInfo toInfo(String objectKey, VersionEntry e) {
        return new VersionInfo(e.versionId, objectKey, e.contentId, e.size, e.createdAt, false);
    }

    @Override
    public String commitVersion(String objectKey, String contentId, long size) {
        // 先 retain：保证元数据引用内容时引用计数已 +1
        contentStore.retain(contentId);
        synchronized (lockOf(objectKey)) {
            ObjectMeta meta = metas.computeIfAbsent(objectKey, k -> {
                ObjectMeta m = new ObjectMeta();
                m.objectKey = k;
                return m;
            });
            String versionId = UUID.randomUUID().toString();
            meta.versions.add(new VersionEntry(versionId, contentId, size, System.currentTimeMillis()));
            persistMeta(meta);
            log.info("提交新版本 object={} versionId={} contentId={} size={}",
                    objectKey, versionId, contentId, size);
            return versionId;
        }
    }

    @Override
    public ObjectData readLatest(String objectKey) {
        synchronized (lockOf(objectKey)) {
            ObjectMeta meta = metas.get(objectKey);
            if (meta == null || meta.versions.isEmpty()) {
                throw new StorageException(StorageException.ErrorCode.OBJECT_NOT_FOUND,
                        "对象不存在: " + objectKey);
            }
            VersionEntry latest = meta.versions.get(meta.versions.size() - 1);
            return new ObjectData(toInfo(objectKey, latest), contentStore.open(latest.contentId));
        }
    }

    @Override
    public ObjectData readVersion(String objectKey, String versionId) {
        synchronized (lockOf(objectKey)) {
            VersionEntry entry = findEntry(objectKey, versionId);
            return new ObjectData(toInfo(objectKey, entry), contentStore.open(entry.contentId));
        }
    }

    @Override
    public RangeData readRangeLatest(String objectKey, long start, long end) {
        synchronized (lockOf(objectKey)) {
            ObjectMeta meta = metas.get(objectKey);
            if (meta == null || meta.versions.isEmpty()) {
                throw new StorageException(StorageException.ErrorCode.OBJECT_NOT_FOUND,
                        "对象不存在: " + objectKey);
            }
            VersionEntry latest = meta.versions.get(meta.versions.size() - 1);
            return openRange(objectKey, latest, start, end);
        }
    }

    @Override
    public RangeData readRangeVersion(String objectKey, String versionId, long start, long end) {
        synchronized (lockOf(objectKey)) {
            return openRange(objectKey, findEntry(objectKey, versionId), start, end);
        }
    }

    /**
     * 在对象锁内调用：校验范围、打开内容流（加读钉）、跳过前缀并限制长度。
     * 打开失败会释放读钉，因此不会泄漏钉住的内容。
     */
    private RangeData openRange(String objectKey, VersionEntry entry, long start, long end) {
        if (start < 0 || end < start) {
            throw new StorageException(StorageException.ErrorCode.INVALID_RANGE,
                    "非法范围: start=" + start + ", end=" + end);
        }
        long size = entry.size;
        if (start >= size) {
            throw new StorageException(StorageException.ErrorCode.RANGE_NOT_SATISFIABLE,
                    "起始偏移 " + start + " 超出对象长度 " + size
                            + ": " + objectKey + "@" + entry.versionId);
        }
        long actualEnd = Math.min(end, size - 1);
        InputStream raw = contentStore.open(entry.contentId);
        try {
            skipFully(raw, start);
            InputStream bounded = new BoundedInputStream(raw, actualEnd - start + 1);
            return new RangeData(toInfo(objectKey, entry), size, start, actualEnd,
                    actualEnd < size - 1, bounded);
        } catch (IOException | RuntimeException e) {
            try {
                raw.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e instanceof RuntimeException re ? re : new UncheckedIOException((IOException) e);
        }
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    throw new StorageException(StorageException.ErrorCode.STORAGE_CORRUPT,
                            "内容流提前结束，无法跳过 " + bytes + " 字节");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    /** 只暴露前 {@code limit} 字节的流；关闭时会关闭底层流（从而释放读钉）。 */
    private static final class BoundedInputStream extends java.io.FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
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
    }

    private VersionEntry findEntry(String objectKey, String versionId) {
        ObjectMeta meta = metas.get(objectKey);
        if (meta == null) {
            throw new StorageException(StorageException.ErrorCode.OBJECT_NOT_FOUND,
                    "对象不存在: " + objectKey);
        }
        return meta.versions.stream()
                .filter(v -> v.versionId.equals(versionId))
                .findFirst()
                .orElseThrow(() -> new StorageException(StorageException.ErrorCode.VERSION_NOT_FOUND,
                        "版本不存在: " + objectKey + "@" + versionId));
    }

    @Override
    public List<VersionInfo> listVersions(String objectKey) {
        synchronized (lockOf(objectKey)) {
            ObjectMeta meta = metas.get(objectKey);
            if (meta == null) {
                throw new StorageException(StorageException.ErrorCode.OBJECT_NOT_FOUND,
                        "对象不存在: " + objectKey);
            }
            List<VersionInfo> result = new ArrayList<>();
            for (int i = meta.versions.size() - 1; i >= 0; i--) {
                result.add(toInfo(objectKey, meta.versions.get(i)));
            }
            return result;
        }
    }

    @Override
    public void deleteVersion(String objectKey, String versionId) {
        String contentToRelease;
        synchronized (lockOf(objectKey)) {
            ObjectMeta meta = metas.get(objectKey);
            VersionEntry entry = findEntry(objectKey, versionId);
            contentToRelease = entry.contentId;
            meta.versions.remove(entry);
            if (meta.versions.isEmpty()) {
                metas.remove(objectKey, meta);
                removeMetaFile(objectKey);
            } else {
                persistMeta(meta);
            }
            log.info("删除版本 object={} versionId={} contentId={}", objectKey, versionId, contentToRelease);
        }
        // 元数据已落盘后再释放引用，回收线程不会误删仍被引用的内容
        contentStore.release(contentToRelease);
    }

    @Override
    public void deleteObject(String objectKey) {
        List<String> contentsToRelease = new ArrayList<>();
        synchronized (lockOf(objectKey)) {
            ObjectMeta meta = metas.remove(objectKey);
            if (meta == null) {
                throw new StorageException(StorageException.ErrorCode.OBJECT_NOT_FOUND,
                        "对象不存在: " + objectKey);
            }
            for (VersionEntry e : meta.versions) {
                contentsToRelease.add(e.contentId);
            }
            removeMetaFile(objectKey);
            log.info("删除对象 object={} 共 {} 个版本", objectKey, contentsToRelease.size());
        }
        contentsToRelease.forEach(contentStore::release);
    }

    @Override
    public java.util.Map<String, Integer> contentReferences() {
        java.util.Map<String, Integer> refs = new java.util.HashMap<>();
        for (ObjectMeta meta : metas.values()) {
            synchronized (lockOf(meta.objectKey)) {
                for (VersionEntry e : meta.versions) {
                    refs.merge(e.contentId, 1, Integer::sum);
                }
            }
        }
        return refs;
    }

    @Override
    public String rollback(String objectKey, String versionId) {
        String contentId;
        long size;
        synchronized (lockOf(objectKey)) {
            VersionEntry entry = findEntry(objectKey, versionId);
            contentId = entry.contentId;
            size = entry.size;
        }
        // commitVersion 内部会 retain 内容并追加新版本
        String newVersionId = commitVersion(objectKey, contentId, size);
        log.info("回滚 object={} 到版本 {}，生成新版本 {}", objectKey, versionId, newVersionId);
        return newVersionId;
    }
}
