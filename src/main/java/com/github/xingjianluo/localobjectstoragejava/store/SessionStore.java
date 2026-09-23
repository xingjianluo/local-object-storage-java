package com.github.xingjianluo.localobjectstoragejava.store;

import com.github.xingjianluo.localobjectstoragejava.config.StorageProperties;
import com.github.xingjianluo.localobjectstoragejava.model.PartInfo;
import com.github.xingjianluo.localobjectstoragejava.model.UploadSession;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * 上传会话与分片的磁盘存储。
 *
 * <p>布局：{@code <root>/uploads/<sid前2位>/<sid>/session.json + parts/part-NNNNN}。
 * 会话 JSON 通过临时文件 + 原子 move 发布，保证崩溃后不会读到半个 JSON。
 * 每个会话一把锁，串行化同一会话的状态变更；不同会话互不阻塞。</p>
 */
@Component
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    private final Path uploadsDir;
    private final JsonCodec jsonCodec;
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public SessionStore(StorageProperties properties, JsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
        this.uploadsDir = Path.of(properties.getRootDir(), "uploads");
        createDirectories(this.uploadsDir);
    }

    private static void createDirectories(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("创建目录失败: " + path, e);
        }
    }

    /**
     * 取（必要时创建）会话锁。会话被物理删除后锁会被调用方清理。
     */
    private Object lockOf(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, k -> new Object());
    }

    private Path sessionDir(String sessionId) {
        return uploadsDir.resolve(sessionId.substring(0, 2)).resolve(sessionId);
    }

    private Path sessionJsonFile(String sessionId) {
        return sessionDir(sessionId).resolve("session.json");
    }

    public Path partPath(String sessionId, int partNumber) {
        return sessionDir(sessionId).resolve("parts")
                .resolve(String.format("part-%05d", partNumber));
    }

    public UploadSession create(String objectKey, int totalParts, long partSize, long ttlMillis) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        UploadSession session = new UploadSession();
        session.setSessionId(sessionId);
        session.setObjectKey(objectKey);
        session.setTotalParts(totalParts);
        session.setPartSize(partSize);
        session.setState(UploadSession.State.INIT);
        session.setCreatedAtMillis(now);
        session.setExpiresAtMillis(now + ttlMillis);
        createDirectories(sessionDir(sessionId).resolve("parts"));
        persistSession(session);
        log.info("创建上传会话 sid={} key={} totalParts={} partSize={} expiresAt={}",
                sessionId, objectKey, totalParts, partSize, session.getExpiresAtMillis());
        return session;
    }

    public UploadSession load(String sessionId) {
        Path jsonFile = sessionJsonFile(sessionId);
        if (!Files.isRegularFile(jsonFile)) {
            return null;
        }
        try {
            String json = Files.readString(jsonFile, StandardCharsets.UTF_8);
            return jsonCodec.fromJson(json, UploadSession.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取会话失败: " + sessionId, e);
        }
    }

    /**
     * 在会话锁内执行读-改-写，保证同一会话的并发操作串行且状态一致。
     */
    public UploadSession mutate(String sessionId, UnaryOperator<UploadSession> mutation) {
        synchronized (lockOf(sessionId)) {
            UploadSession current = load(sessionId);
            if (current == null) {
                return null;
            }
            UploadSession updated = mutation.apply(current);
            if (updated != null) {
                persistSession(updated);
            }
            return updated;
        }
    }

    public void persistSession(UploadSession session) {
        Path jsonFile = sessionJsonFile(session.getSessionId());
        Path tmp = jsonFile.resolveSibling("session.json.tmp");
        try {
            Files.writeString(tmp, jsonCodec.toJson(session), StandardCharsets.UTF_8);
            Files.move(tmp, jsonFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("持久化会话失败: " + session.getSessionId(), e);
        }
    }

    /**
     * 只删除分片数据目录，保留 session.json（用于终态后的重复请求给出可区分错误）。
     */
    public void removeParts(String sessionId) {
        synchronized (lockOf(sessionId)) {
            Path parts = sessionDir(sessionId).resolve("parts");
            deleteRecursively(parts);
            // 删除遗留的 upload-*.tmp（失败重试残留）。
            log.debug("已清理分片数据 sid={}", sessionId);
        }
    }

    public void removeSession(String sessionId) {
        synchronized (lockOf(sessionId)) {
            deleteRecursively(sessionDir(sessionId));
            sessionLocks.remove(sessionId);
            log.info("删除上传会话目录 sid={}", sessionId);
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException("删除失败: " + p, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("遍历删除失败: " + root, e);
        }
    }

    public List<UploadSession> listSessions() {
        List<UploadSession> result = new ArrayList<>();
        if (!Files.isDirectory(uploadsDir)) {
            return result;
        }
        try (var shards = Files.list(uploadsDir)) {
            shards.filter(Files::isDirectory).forEach(shard -> {
                try (var dirs = Files.list(shard)) {
                    dirs.filter(Files::isDirectory).forEach(dir -> {
                        Path jsonFile = dir.resolve("session.json");
                        if (Files.isRegularFile(jsonFile)) {
                            try {
                                result.add(jsonCodec.fromJson(
                                        Files.readString(jsonFile, StandardCharsets.UTF_8),
                                        UploadSession.class));
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }
                    });
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("列举会话失败", e);
        }
        return result;
    }

    public PartInfo newPartInfo(int number, long size, String checksum) {
        return new PartInfo(number, size, checksum, System.currentTimeMillis());
    }
}
