package com.github.xingjianluo.localobjectstoragejava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于本地磁盘的会话与分片管理实现。
 *
 * <p>布局：{@code <root>/sessions/<sessionId>/session.json} 保存会话元数据，
 * {@code part-<n>} 为已校验的分片文件。分片先写临时文件、校验 sha256 后原子落位，
 * 因此任何时刻读到的分片都是完整的；合并通过 {@link SequenceInputStream}
 * 流式进行，内存占用与对象总大小无关。</p>
 */
@Component
public class LocalUploadSessionManager implements UploadSessionManager {

    private static final Logger log = LoggerFactory.getLogger(LocalUploadSessionManager.class);

    /** 会话元数据（持久化到 session.json） */
    static class SessionMeta {
        public String sessionId;
        public String objectKey;
        public List<PartSpec> parts = new ArrayList<>();
        public Set<Integer> received = new HashSet<>();
        public long expiresAt;
        public boolean completed;
        public boolean aborted;
    }

    private final StorageProperties properties;
    private final ContentStore contentStore;
    private final ObjectStorageService objectStorageService;
    private final Path sessionsDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionMeta> sessions = new ConcurrentHashMap<>();

    public LocalUploadSessionManager(StorageProperties properties,
                                     ContentStore contentStore,
                                     ObjectStorageService objectStorageService) {
        this.properties = properties;
        this.contentStore = contentStore;
        this.objectStorageService = objectStorageService;
        this.sessionsDir = Path.of(properties.getRootDir()).resolve("sessions");
        try {
            Files.createDirectories(sessionsDir);
            loadAll();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void loadAll() throws IOException {
        try (var stream = Files.list(sessionsDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                Path metaFile = dir.resolve("session.json");
                if (Files.exists(metaFile)) {
                    SessionMeta meta = mapper.readValue(metaFile.toFile(), SessionMeta.class);
                    sessions.put(meta.sessionId, meta);
                }
            }
        }
        log.info("UploadSessionManager 启动，加载 {} 个会话", sessions.size());
    }

    private Object lockOf(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, k -> new Object());
    }

    private Path sessionDir(String sessionId) {
        return sessionsDir.resolve(sessionId);
    }

    private Path partPath(String sessionId, int partNumber) {
        return sessionDir(sessionId).resolve("part-" + partNumber);
    }

    private void persistMeta(SessionMeta meta) {
        try {
            Path dir = sessionDir(meta.sessionId);
            Files.createDirectories(dir);
            Path tmp = dir.resolve("session.json.tmp");
            mapper.writeValue(tmp.toFile(), meta);
            Files.move(tmp, dir.resolve("session.json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String createSession(String objectKey, List<PartSpec> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new StorageException(StorageException.ErrorCode.PARTS_MISSING,
                    "分片列表不能为空");
        }
        // 分片编号必须恰好为 1..N
        Set<Integer> numbers = new HashSet<>();
        for (PartSpec p : parts) {
            if (p.partNumber() < 1 || p.partNumber() > parts.size() || !numbers.add(p.partNumber())) {
                throw new StorageException(StorageException.ErrorCode.PART_NUMBER_OUT_OF_RANGE,
                        "分片编号必须恰好覆盖 1.." + parts.size() + "，非法编号: " + p.partNumber());
            }
            if (p.size() > properties.getMaxPartSize()) {
                throw new StorageException(StorageException.ErrorCode.PART_SIZE_MISMATCH,
                        "分片 " + p.partNumber() + " 超过最大允许大小");
            }
        }
        SessionMeta meta = new SessionMeta();
        meta.sessionId = UUID.randomUUID().toString();
        meta.objectKey = objectKey;
        meta.parts = new ArrayList<>(parts);
        meta.expiresAt = System.currentTimeMillis() + properties.getSessionTtl().toMillis();
        synchronized (lockOf(meta.sessionId)) {
            sessions.put(meta.sessionId, meta);
            persistMeta(meta);
        }
        log.info("创建上传会话 sessionId={} object={} partCount={} expiresAt={}",
                meta.sessionId, objectKey, parts.size(), meta.expiresAt);
        return meta.sessionId;
    }

    /** 校验会话可操作，返回元数据；否则抛出带可区分错误码的异常。 */
    private SessionMeta requireActive(String sessionId) {
        SessionMeta meta = sessions.get(sessionId);
        if (meta == null) {
            throw new StorageException(StorageException.ErrorCode.SESSION_NOT_FOUND,
                    "会话不存在: " + sessionId);
        }
        if (meta.completed) {
            throw new StorageException(StorageException.ErrorCode.SESSION_ALREADY_COMPLETED,
                    "会话已完成: " + sessionId);
        }
        if (meta.aborted) {
            throw new StorageException(StorageException.ErrorCode.SESSION_ALREADY_ABORTED,
                    "会话已中止: " + sessionId);
        }
        if (System.currentTimeMillis() > meta.expiresAt) {
            throw new StorageException(StorageException.ErrorCode.SESSION_EXPIRED,
                    "会话已过期: " + sessionId);
        }
        return meta;
    }

    private PartSpec specOf(SessionMeta meta, int partNumber) {
        if (partNumber < 1 || partNumber > meta.parts.size()) {
            throw new StorageException(StorageException.ErrorCode.PART_NUMBER_OUT_OF_RANGE,
                    "分片编号越界: " + partNumber + "，合法范围 1.." + meta.parts.size());
        }
        return meta.parts.get(partNumber - 1);
    }

    @Override
    public void uploadPart(String sessionId, int partNumber, InputStream data, long size) {
        synchronized (lockOf(sessionId)) {
            SessionMeta meta = requireActive(sessionId);
            PartSpec spec = specOf(meta, partNumber);
            if (size != spec.size()) {
                throw new StorageException(StorageException.ErrorCode.PART_SIZE_MISMATCH,
                        "分片 " + partNumber + " 声明大小 " + spec.size() + " 与实际 " + size + " 不一致");
            }
            // 1. 流式写临时文件并计算 sha256
            MessageDigest digest = newDigest();
            Path tmp;
            try {
                tmp = Files.createTempFile(sessionDir(sessionId), "upload-", ".part");
                byte[] buf = new byte[64 * 1024];
                int n;
                var out = Files.newOutputStream(tmp);
                try (out) {
                    while ((n = data.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        digest.update(buf, 0, n);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            // 2. 校验 sha256，失败则删除临时文件并拒绝
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(spec.sha256())) {
                deleteQuietly(tmp);
                throw new StorageException(StorageException.ErrorCode.PART_CHECKSUM_MISMATCH,
                        "分片 " + partNumber + " 校验失败: 期望 sha256=" + spec.sha256()
                                + " 实际 sha256=" + actual);
            }
            // 3. 原子落位（重复上传直接覆盖，内容一致故幂等）
            try {
                Files.move(tmp, partPath(sessionId, partNumber),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                deleteQuietly(tmp);
                throw new UncheckedIOException(e);
            }
            boolean duplicate = !meta.received.add(partNumber);
            persistMeta(meta);
            log.info("分片上传 sessionId={} part={} size={} sha256={} duplicate={}",
                    sessionId, partNumber, size, actual, duplicate);
        }
    }

    @Override
    public SessionInfo sessionInfo(String sessionId) {
        synchronized (lockOf(sessionId)) {
            SessionMeta meta = sessions.get(sessionId);
            if (meta == null) {
                throw new StorageException(StorageException.ErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId);
            }
            List<Integer> received = new ArrayList<>(meta.received);
            Collections.sort(received);
            return new SessionInfo(meta.sessionId, meta.objectKey, meta.parts.size(),
                    meta.expiresAt, meta.completed, meta.aborted, received);
        }
    }

    @Override
    public String complete(String sessionId) {
        synchronized (lockOf(sessionId)) {
            SessionMeta meta = requireActive(sessionId);
            // 1. 全部分片必须已上传
            List<Integer> missing = new ArrayList<>();
            for (PartSpec p : meta.parts) {
                if (!meta.received.contains(p.partNumber())) {
                    missing.add(p.partNumber());
                }
            }
            if (!missing.isEmpty()) {
                throw new StorageException(StorageException.ErrorCode.PARTS_MISSING,
                        "缺少分片: " + missing);
            }
            // 2. 合并前重新校验每个分片（防御磁盘损坏/外部篡改）
            for (PartSpec p : meta.parts) {
                verifyPartOnDisk(sessionId, p);
            }
            // 3. 流式合并写入内容存储，内存占用与对象大小无关
            long totalSize = meta.parts.stream().mapToLong(PartSpec::size).sum();
            List<InputStream> streams = new ArrayList<>();
            try {
                for (PartSpec p : meta.parts) {
                    streams.add(Files.newInputStream(partPath(sessionId, p.partNumber())));
                }
            } catch (IOException e) {
                closeAll(streams);
                throw new UncheckedIOException(e);
            }
            Enumeration<InputStream> enumeration = Collections.enumeration(streams);
            String contentId;
            try (InputStream merged = new SequenceInputStream(enumeration)) {
                contentId = contentStore.put(merged, totalSize);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            // 4. 提交新版本（内部 retain），随后释放 put 持有的引用，净效果为版本持有 1 个引用
            String versionId;
            try {
                versionId = objectStorageService.commitVersion(meta.objectKey, contentId, totalSize);
            } finally {
                contentStore.release(contentId);
            }
            // 5. 标记完成并清理分片文件
            meta.completed = true;
            persistMeta(meta);
            cleanupParts(sessionId, meta.parts.size());
            log.info("会话完成 sessionId={} object={} versionId={} contentId={} size={}",
                    sessionId, meta.objectKey, versionId, contentId, totalSize);
            return versionId;
        }
    }

    private void verifyPartOnDisk(String sessionId, PartSpec spec) {
        Path part = partPath(sessionId, spec.partNumber());
        if (!Files.exists(part)) {
            throw new StorageException(StorageException.ErrorCode.PARTS_MISSING,
                    "分片文件丢失: " + spec.partNumber());
        }
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(spec.sha256())) {
            throw new StorageException(StorageException.ErrorCode.PART_CHECKSUM_MISMATCH,
                    "合并前校验失败，分片 " + spec.partNumber() + " 期望 sha256=" + spec.sha256()
                            + " 实际 sha256=" + actual);
        }
    }

    @Override
    public void abort(String sessionId) {
        synchronized (lockOf(sessionId)) {
            SessionMeta meta = sessions.get(sessionId);
            if (meta == null) {
                throw new StorageException(StorageException.ErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId);
            }
            if (meta.completed) {
                throw new StorageException(StorageException.ErrorCode.SESSION_ALREADY_COMPLETED,
                        "会话已完成，无法中止: " + sessionId);
            }
            meta.aborted = true;
            persistMeta(meta);
            cleanupParts(sessionId, meta.parts.size());
            log.info("会话中止 sessionId={} object={}", sessionId, meta.objectKey);
        }
    }

    @Override
    public List<String> expireSessions() {
        List<String> expired = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (SessionMeta meta : sessions.values()) {
            synchronized (lockOf(meta.sessionId)) {
                if (!meta.completed && !meta.aborted && now > meta.expiresAt) {
                    meta.aborted = true;
                    persistMeta(meta);
                    cleanupParts(meta.sessionId, meta.parts.size());
                    expired.add(meta.sessionId);
                    log.info("会话过期回收 sessionId={} object={}", meta.sessionId, meta.objectKey);
                }
            }
        }
        return expired;
    }

    private void cleanupParts(String sessionId, int partCount) {
        for (int i = 1; i <= partCount; i++) {
            deleteQuietly(partPath(sessionId, i));
        }
    }

    private static void closeAll(List<InputStream> streams) {
        for (InputStream s : streams) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
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
