package com.github.xingjianluo.localobjectstoragejava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
                // 清理元数据写入中断遗留的临时文件
                Files.deleteIfExists(dir.resolve("meta.json.tmp"));
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
    public RangeData readLatest(String objectKey) {
        synchronized (lockOf(objectKey)) {
            ObjectMeta meta = metas.get(objectKey);
            if (meta == null || meta.versions.isEmpty()) {
                throw new StorageException(StorageException.ErrorCode.OBJECT_NOT_FOUND,
                        "对象不存在: " + objectKey);
            }
            VersionEntry latest = meta.versions.get(meta.versions.size() - 1);
            // 整包与按段共用同一返回：整包即 [0, size)、hasMore=false
            return openFull(objectKey, latest);
        }
    }

    @Override
    public RangeData readVersion(String objectKey, String versionId) {
        synchronized (lockOf(objectKey)) {
            return openFull(objectKey, findEntry(objectKey, versionId));
        }
    }

    /** 在对象锁内调用：打开整包读取流（offset=0, length=size, hasMore=false）。 */
    private RangeData openFull(String objectKey, VersionEntry entry) {
        return new RangeData(toInfo(objectKey, entry), entry.size, 0, entry.size,
                false, contentStore.open(entry.contentId));
    }

    @Override
    public RangeData readLatestRange(String objectKey, long offset, long length) {
        synchronized (lockOf(objectKey)) {
            ObjectMeta meta = metas.get(objectKey);
            if (meta == null || meta.versions.isEmpty()) {
                throw new StorageException(StorageException.ErrorCode.OBJECT_NOT_FOUND,
                        "对象不存在: " + objectKey);
            }
            return openNumericRange(objectKey, meta.versions.get(meta.versions.size() - 1),
                    offset, length);
        }
    }

    @Override
    public RangeData readVersionRange(String objectKey, String versionId, long offset, long length) {
        synchronized (lockOf(objectKey)) {
            return openNumericRange(objectKey, findEntry(objectKey, versionId), offset, length);
        }
    }

    @Override
    public RangeData readLatestRange(String objectKey, ByteRange range) {
        synchronized (lockOf(objectKey)) {
            ObjectMeta meta = metas.get(objectKey);
            if (meta == null || meta.versions.isEmpty()) {
                throw new StorageException(StorageException.ErrorCode.OBJECT_NOT_FOUND,
                        "对象不存在: " + objectKey);
            }
            return openSpecRange(objectKey, meta.versions.get(meta.versions.size() - 1), range);
        }
    }

    @Override
    public RangeData readVersionRange(String objectKey, String versionId, ByteRange range) {
        synchronized (lockOf(objectKey)) {
            return openSpecRange(objectKey, findEntry(objectKey, versionId), range);
        }
    }

    /** 在对象锁内调用：编程式 [offset, offset+length) 入参的写法校验与开流。 */
    private RangeData openNumericRange(String objectKey, VersionEntry entry, long offset, long length) {
        if (offset < 0 || length <= 0) {
            throw new StorageException(StorageException.ErrorCode.INVALID_RANGE,
                    "非法范围: offset=" + offset + " length=" + length
                            + "（要求 offset>=0 且 length>0）");
        }
        return openResolved(objectKey, entry, new ByteRange.Resolved(offset, length));
    }

    /** 在对象锁内调用：把范围写法对照该版本长度解析并开流（起点越界抛 416 类错误）。 */
    private RangeData openSpecRange(String objectKey, VersionEntry entry, ByteRange range) {
        if (range == null) {
            throw new StorageException(StorageException.ErrorCode.INVALID_RANGE, "范围为空");
        }
        // resolve 与 openRange 在同一对象锁内：版本长度与内容必定来自同一个版本
        return openResolved(objectKey, entry, range.resolve(entry.size));
    }

    /** 在对象锁内调用：按已解析区间截断末尾、加钉并打开定界流。 */
    private RangeData openResolved(String objectKey, VersionEntry entry, ByteRange.Resolved resolved) {
        long offset = resolved.offset();
        long length = resolved.length();
        if (offset >= entry.size) {
            throw new StorageException(StorageException.ErrorCode.RANGE_NOT_SATISFIABLE,
                    "起始位置 " + offset + " 已达到或超过对象长度 " + entry.size);
        }
        long actualLength = Math.min(length, entry.size - offset);
        boolean hasMore = offset + actualLength < entry.size;
        return new RangeData(toInfo(objectKey, entry), entry.size, offset, actualLength,
                hasMore, contentStore.openRange(entry.contentId, offset, actualLength));
    }

    @Override
    public VersionInfo statLatest(String objectKey) {
        synchronized (lockOf(objectKey)) {
            ObjectMeta meta = metas.get(objectKey);
            if (meta == null || meta.versions.isEmpty()) {
                throw new StorageException(StorageException.ErrorCode.OBJECT_NOT_FOUND,
                        "对象不存在: " + objectKey);
            }
            return toInfo(objectKey, meta.versions.get(meta.versions.size() - 1));
        }
    }

    @Override
    public VersionInfo statVersion(String objectKey, String versionId) {
        synchronized (lockOf(objectKey)) {
            return toInfo(objectKey, findEntry(objectKey, versionId));
        }
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
