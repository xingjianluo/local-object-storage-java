package com.github.xingjianluo.localobjectstoragejava.service;

import com.github.xingjianluo.localobjectstoragejava.config.StorageProperties;
import com.github.xingjianluo.localobjectstoragejava.model.ErrorCode;
import com.github.xingjianluo.localobjectstoragejava.model.ObjectMeta;
import com.github.xingjianluo.localobjectstoragejava.model.ObjectVersion;
import com.github.xingjianluo.localobjectstoragejava.model.PartInfo;
import com.github.xingjianluo.localobjectstoragejava.model.StorageException;
import com.github.xingjianluo.localobjectstoragejava.model.UploadSession;
import com.github.xingjianluo.localobjectstoragejava.store.BlobStore;
import com.github.xingjianluo.localobjectstoragejava.store.BoundedInputStream;
import com.github.xingjianluo.localobjectstoragejava.store.ChecksumUtil;
import com.github.xingjianluo.localobjectstoragejava.store.MetadataStore;
import com.github.xingjianluo.localobjectstoragejava.store.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 分片上传协议实现。
 *
 * <p>协议：{@code 创建会话 -> 乱序/重试上传分片 -> 校验合并完成}。
 * 只有在全部分片齐全、整体校验通过且版本元数据提交后，对象才对读取方可见。</p>
 *
 * <p>大小规则：除最后一片外每片必须恰好为 partSize，最后一片 1..partSize；
 * 每片携带 SHA-256，完成时携带整体 SHA-256。重复上传同编号同内容的分片幂等成功，
 * 同编号不同内容拒绝（PART_CONFLICT），避免半截/错版分片互相覆盖。</p>
 *
 * <p>并发：同一会话的所有变更串行于会话锁；版本提交串行于对象 key 锁；
 * 需要同时持有两把锁时顺序固定为“会话锁 -> key 锁”（仅 complete），
 * 与读取路径“只取 key 锁”不构成环路。</p>
 */
@Service
public class MultipartUploadService {

    private static final Logger log = LoggerFactory.getLogger(MultipartUploadService.class);

    private final SessionStore sessionStore;
    private final BlobStore blobStore;
    private final MetadataStore metadataStore;
    private final StorageProperties properties;

    public MultipartUploadService(SessionStore sessionStore, BlobStore blobStore,
                                  MetadataStore metadataStore, StorageProperties properties) {
        this.sessionStore = sessionStore;
        this.blobStore = blobStore;
        this.metadataStore = metadataStore;
        this.properties = properties;
    }

    // ------------------------------------------------------------------
    // 创建会话
    // ------------------------------------------------------------------

    public UploadSession createSession(String objectKey, int totalParts, long partSize) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new StorageException(ErrorCode.OBJECT_NOT_FOUND, "objectKey 不能为空");
        }
        if (totalParts <= 0) {
            throw new StorageException(ErrorCode.PART_NUMBER_OUT_OF_RANGE, "totalParts 必须为正整数");
        }
        if (partSize <= 0) {
            throw new StorageException(ErrorCode.PART_SIZE_MISMATCH, "partSize 必须为正数");
        }
        return sessionStore.create(objectKey, totalParts, partSize, properties.getSessionTtlMillis());
    }

    // ------------------------------------------------------------------
    // 上传分片
    // ------------------------------------------------------------------

    public UploadSession uploadPart(String sessionId, int partNumber, long expectedSize,
                                    String expectedChecksum, InputStream content) {
        if (partNumber < 1) {
            throw new StorageException(ErrorCode.PART_NUMBER_OUT_OF_RANGE,
                    "分片编号必须 >= 1，实际: " + partNumber);
        }
        UploadSession loaded = sessionStore.load(sessionId);
        if (loaded == null) {
            throw new StorageException(ErrorCode.SESSION_NOT_FOUND, "上传会话不存在: " + sessionId);
        }
        if (partNumber > loaded.getTotalParts()) {
            throw new StorageException(ErrorCode.PART_NUMBER_OUT_OF_RANGE,
                    String.format("分片编号越界: number=%d totalParts=%d", partNumber, loaded.getTotalParts()));
        }

        // 先落临时文件并校验，再在会话锁内提交，避免慢 IO 长时间持锁，
        // 也保证会话元数据不会记录一个校验失败的分片。
        Path sessionPartsDir = sessionStore.partPath(sessionId, partNumber).getParent();
        Path tmp = null;
        long actualSize = -1;
        String actualChecksum = null;
        try {
            Files.createDirectories(sessionPartsDir);
            tmp = Files.createTempFile(sessionPartsDir, "upload-", ".tmp");
            try (InputStream in = new BoundedInputStream(new BufferedInputStream(content), expectedSize)) {
                actualSize = Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                try (InputStream checkIn = Files.newInputStream(tmp)) {
                    actualChecksum = ChecksumUtil.sha256Hex(checkIn);
                }
            }
            if (actualSize != expectedSize) {
                deleteQuietly(tmp);
                throw new StorageException(ErrorCode.PART_SIZE_MISMATCH,
                        String.format("分片 %d 大小不符: declared=%d actual=%d",
                                partNumber, expectedSize, actualSize));
            }
            if (expectedChecksum != null && !expectedChecksum.equalsIgnoreCase(actualChecksum)) {
                deleteQuietly(tmp);
                throw new StorageException(ErrorCode.PART_CHECKSUM_MISMATCH,
                        String.format("分片 %d 校验失败: declared=%s actual=%s",
                                partNumber, expectedChecksum, actualChecksum));
            }
            log.info("分片落盘校验通过 sid={} part={}/{} size={} checksum={}",
                    sessionId, partNumber, loaded.getTotalParts(), actualSize, actualChecksum);
        } catch (IOException e) {
            if (tmp != null) {
                deleteQuietly(tmp);
            }
            throw new UncheckedIOException("分片落盘失败: " + sessionId, e);
        } catch (RuntimeException e) {
            if (tmp != null) {
                deleteQuietly(tmp);
            }
            throw e;
        }

        // 以下锁内判定一律以“实际落盘内容”的大小/checksum 为准，
        // 避免“旧声明 + 篡改内容”的重试被误判为幂等。
        final long finalActualSize = actualSize;
        final String finalActualChecksum = actualChecksum;
        final Path finalTmp = tmp;
        UploadSession updated = sessionStore.mutate(sessionId, session -> {
            switch (session.getState()) {
                case COMPLETED -> throw new StorageException(ErrorCode.SESSION_COMPLETED,
                        "会话已完成，不能再上传分片: " + sessionId);
                case ABORTED -> throw new StorageException(ErrorCode.SESSION_ABORTED,
                        "会话已中止: " + sessionId);
                default -> {
                    if (session.isExpired(System.currentTimeMillis())) {
                        throw new StorageException(ErrorCode.SESSION_EXPIRED,
                                "会话已过期: " + sessionId);
                    }
                }
            }
            PartInfo existing = session.getParts().get(partNumber);
            if (existing != null) {
                if (existing.sameContent(finalActualSize, finalActualChecksum)) {
                    // 同编号且本次上传内容与已存内容逐字节同值（size+checksum 一致）= 客户端重试，
                    // 幂等成功，临时文件直接丢弃。
                    deleteQuietly(finalTmp);
                    log.info("分片重复上传(幂等) sid={} part={} size={} checksum={}",
                            sessionId, partNumber, finalActualSize, finalActualChecksum);
                    return session;
                }
                deleteQuietly(finalTmp);
                throw new StorageException(ErrorCode.PART_CONFLICT,
                        String.format("分片 %d 已存在且内容不同: existing(size=%d,sha=%s) incoming(size=%d,sha=%s)",
                                partNumber, existing.getSize(), existing.getChecksum(),
                                finalActualSize, finalActualChecksum));
            }
            // 大小规则：非最后一片必须恰好 partSize；最后一片 1..partSize。
            boolean isLast = partNumber == session.getTotalParts();
            if (!isLast && finalActualSize != session.getPartSize()) {
                deleteQuietly(finalTmp);
                throw new StorageException(ErrorCode.PART_SIZE_MISMATCH,
                        String.format("非末尾分片 %d 必须恰好为 partSize=%d，实际=%d",
                                partNumber, session.getPartSize(), finalActualSize));
            }
            if (isLast && (finalActualSize <= 0 || finalActualSize > session.getPartSize())) {
                deleteQuietly(finalTmp);
                throw new StorageException(ErrorCode.PART_SIZE_MISMATCH,
                        String.format("末尾分片 %d 大小必须在 1..partSize(%d) 内，实际=%d",
                                partNumber, session.getPartSize(), finalActualSize));
            }
            Path target = sessionStore.partPath(sessionId, partNumber);
            try {
                Files.move(finalTmp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException("提交分片失败: " + partNumber, e);
            }
            session.getParts().put(partNumber,
                    sessionStore.newPartInfo(partNumber, finalActualSize, finalActualChecksum));
            log.info("分片已提交 sid={} part={} 已收齐 {}/{}",
                    sessionId, partNumber, session.getParts().size(), session.getTotalParts());
            return session;
        });
        if (updated == null) {
            deleteQuietly(tmp);
            throw new StorageException(ErrorCode.SESSION_NOT_FOUND, "上传会话不存在: " + sessionId);
        }
        return updated;
    }

    // ------------------------------------------------------------------
    // 完成合并
    // ------------------------------------------------------------------

    public UploadSession completeSession(String sessionId, String expectedChecksum) {
        // 第一次检查（无锁）：快速失败 + 拿到分片快照与 key。
        UploadSession first = requireActiveSession(sessionId);
        validateAllPartsPresent(first);

        // 合并在会话锁外做（耗时 IO），但会话处于 INIT 且分片已齐全时，
        // 其它并发操作：重复分片只会幂等返回，abort 与 complete 需在会话锁内重新检查。
        // 为防止“合并中 abort/complete 交错”，合并这一步也在会话锁内完成；
        // 合并是顺序磁盘拷贝，不与不同会话争用。
        UploadSession result = sessionStore.mutate(sessionId, session -> {
            if (session.getState() == UploadSession.State.COMPLETED) {
                throw new StorageException(ErrorCode.SESSION_ALREADY_COMPLETED,
                        "会话已完成，versionId=" + session.getCompletedVersionId()
                                + " contentId=" + session.getContentId());
            }
            if (session.getState() == UploadSession.State.ABORTED) {
                throw new StorageException(ErrorCode.SESSION_ABORTED, "会话已中止: " + sessionId);
            }
            if (session.isExpired(System.currentTimeMillis())) {
                throw new StorageException(ErrorCode.SESSION_EXPIRED, "会话已过期: " + sessionId);
            }
            validateAllPartsPresent(session);

            long totalSize = 0;
            Map<Integer, Path> partFiles = new TreeMap<>();
            for (Map.Entry<Integer, PartInfo> e : session.getParts().entrySet()) {
                partFiles.put(e.getKey(), sessionStore.partPath(sessionId, e.getKey()));
                totalSize += e.getValue().getSize();
            }
            log.info("开始合并 sid={} key={} parts={} totalSize={} expectedSha={}",
                    sessionId, session.getObjectKey(), session.getTotalParts(), totalSize, expectedChecksum);
            BlobStore.StoredContent stored = blobStore.mergeAndPublish(
                    partFiles, session.getTotalParts(), totalSize, expectedChecksum);

            // 提交版本（内部取对象 key 锁；锁序：会话锁 -> key 锁，全系统唯一路径）。
            ObjectVersion version = commitVersion(session.getObjectKey(), stored);

            session.setState(UploadSession.State.COMPLETED);
            session.setCompletedVersionId(version.getVersionId());
            session.setContentId(stored.getContentId());
            session.setTotalSize(stored.getSize());
            session.setTotalChecksum(stored.getChecksum());
            log.info("合并完成对象可见 sid={} key={} versionId={} contentId={} size={} checksum={}",
                    sessionId, session.getObjectKey(), version.getVersionId(),
                    stored.getContentId(), stored.getSize(), stored.getChecksum());
            return session;
        });
        if (result == null) {
            throw new StorageException(ErrorCode.SESSION_NOT_FOUND, "上传会话不存在: " + sessionId);
        }
        // 版本元数据已原子发布后再清理分片目录（大数据即可回收）；保留终态 session.json，
        // 使重复完成能拿到可区分的 SESSION_ALREADY_COMPLETED，终态 JSON 由回收器过期清理。
        sessionStore.removeParts(sessionId);
        return result;
    }

    /**
     * 在对象元数据中追加新版本并获取内容引用。
     * 版本链追加与引用登记在同一把 key 锁内，保证“版本可见 <=> 引用存在”同时成立。
     */
    private ObjectVersion commitVersion(String key, BlobStore.StoredContent stored) {
        ObjectVersion[] holder = new ObjectVersion[1];
        metadataStore.mutate(key, meta -> {
            ObjectVersion previous = meta.currentVersion();
            String versionId = "v" + UUID.randomUUID().toString().replace("-", "");
            ObjectVersion version = new ObjectVersion();
            version.setVersionId(versionId);
            version.setObjectKey(key);
            version.setContentId(stored.getContentId());
            version.setSize(stored.getSize());
            version.setChecksum(stored.getChecksum());
            version.setCreatedAtMillis(System.currentTimeMillis());
            meta.getVersions().add(version);
            meta.setCurrentVersionId(versionId);
            // 先登记引用，再让版本在元数据中可见（mutation 返回后原子落盘）。
            blobStore.acquireRef(stored.getContentId(), key, versionId);
            holder[0] = version;
            // 标记让编译器检查 holder 类型正确
            log.info("提交新版本 key={} versionId={} contentId={} prevVersion={}",
                    key, versionId, stored.getContentId(),
                    previous == null ? "none" : previous.getVersionId());
            return meta;
        });
        return holder[0];
    }

    private UploadSession requireActiveSession(String sessionId) {
        UploadSession session = sessionStore.load(sessionId);
        if (session == null) {
            throw new StorageException(ErrorCode.SESSION_NOT_FOUND, "上传会话不存在: " + sessionId);
        }
        switch (session.getState()) {
            case COMPLETED -> throw new StorageException(ErrorCode.SESSION_ALREADY_COMPLETED,
                    "会话已完成: " + sessionId);
            case ABORTED -> throw new StorageException(ErrorCode.SESSION_ABORTED, "会话已中止: " + sessionId);
            default -> {
                if (session.isExpired(System.currentTimeMillis())) {
                    throw new StorageException(ErrorCode.SESSION_EXPIRED, "会话已过期: " + sessionId);
                }
            }
        }
        return session;
    }

    private void validateAllPartsPresent(UploadSession session) {
        Map<Integer, PartInfo> parts = session.getParts();
        for (int i = 1; i <= session.getTotalParts(); i++) {
            if (!parts.containsKey(i)) {
                throw new StorageException(ErrorCode.PART_MISSING,
                        String.format("分片未收齐，缺少 part=%d，已收=%s", i,
                                new TreeMap<>(parts).keySet()));
            }
        }
    }

    // ------------------------------------------------------------------
    // 中止 / 查询
    // ------------------------------------------------------------------

    public void abortSession(String sessionId) {
        UploadSession session = sessionStore.load(sessionId);
        if (session == null) {
            throw new StorageException(ErrorCode.SESSION_NOT_FOUND, "上传会话不存在: " + sessionId);
        }
        if (session.getState() == UploadSession.State.COMPLETED) {
            throw new StorageException(ErrorCode.SESSION_COMPLETED, "会话已完成，不能中止: " + sessionId);
        }
        sessionStore.mutate(sessionId, s -> {
            if (s.getState() == UploadSession.State.COMPLETED) {
                throw new StorageException(ErrorCode.SESSION_COMPLETED, "会话已完成，不能中止: " + sessionId);
            }
            s.setState(UploadSession.State.ABORTED);
            log.info("会话中止 sid={} key={}", sessionId, s.getObjectKey());
            return s;
        });
        sessionStore.removeSession(sessionId);
    }

    public UploadSession getSession(String sessionId) {
        UploadSession session = sessionStore.load(sessionId);
        if (session == null) {
            throw new StorageException(ErrorCode.SESSION_NOT_FOUND, "上传会话不存在: " + sessionId);
        }
        return session;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
