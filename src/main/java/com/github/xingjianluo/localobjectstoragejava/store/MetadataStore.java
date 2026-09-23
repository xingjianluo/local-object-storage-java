package com.github.xingjianluo.localobjectstoragejava.store;

import com.github.xingjianluo.localobjectstoragejava.config.StorageProperties;
import com.github.xingjianluo.localobjectstoragejava.model.ObjectMeta;
import com.github.xingjianluo.localobjectstoragejava.model.ObjectVersion;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * 对象元数据存储：以对象 key 为单位维护追加式版本链与当前版本指针。
 *
 * <p>布局：{@code <root>/objects/<urlencoded(key)>.json}。
 * 写入走临时文件 + 原子 move；每个 key 一把锁，串行化同对象的版本链变更。</p>
 */
@Component
public class MetadataStore {

    private final Path objectsDir;
    private final JsonCodec jsonCodec;
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();

    public MetadataStore(StorageProperties properties, JsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
        this.objectsDir = Path.of(properties.getRootDir(), "objects");
        try {
            Files.createDirectories(objectsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("创建对象元数据目录失败: " + objectsDir, e);
        }
    }

    private Object lockOf(String key) {
        return keyLocks.computeIfAbsent(key, k -> new Object());
    }

    /**
     * 把对象 key 映射为可逆的安全文件名：非字母数字的字节做 %XX 百分号编码。
     */
    static String encodeKey(String key) {
        StringBuilder sb = new StringBuilder();
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    static String decodeKey(String encoded) {
        byte[] bytes = encoded.getBytes(StandardCharsets.US_ASCII);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '%') {
                out.write(Integer.parseInt(new String(bytes, i + 1, 2, StandardCharsets.US_ASCII), 16));
                i += 2;
            } else {
                out.write(bytes[i]);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private Path metaFile(String key) {
        return objectsDir.resolve(encodeKey(key) + ".json");
    }

    public ObjectMeta load(String key) {
        Path file = metaFile(key);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return jsonCodec.fromJson(Files.readString(file, StandardCharsets.UTF_8), ObjectMeta.class);
        } catch (IOException e) {
            throw new UncheckedIOException("读取对象元数据失败: " + key, e);
        }
    }

    /**
     * 在 key 锁内读-改-写。mutation 返回 null 表示放弃写入。
     */
    public ObjectMeta mutate(String key, UnaryOperator<ObjectMeta> mutation) {
        synchronized (lockOf(key)) {
            ObjectMeta meta = load(key);
            if (meta == null) {
                meta = new ObjectMeta(key);
            }
            ObjectMeta updated = mutation.apply(meta);
            if (updated == null) {
                return meta;
            }
            persist(updated);
            return updated;
        }
    }

    public void persist(ObjectMeta meta) {
        Path file = metaFile(meta.getKey());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, jsonCodec.toJson(meta), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("持久化对象元数据失败: " + meta.getKey(), e);
        }
    }

    public List<String> listKeys() {
        List<String> keys = new ArrayList<>();
        if (!Files.isDirectory(objectsDir)) {
            return keys;
        }
        try (var files = Files.list(objectsDir)) {
            files.map(Path::getFileName).map(Path::toString)
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .map(MetadataStore::decodeKey)
                    .forEach(keys::add);
        } catch (IOException e) {
            throw new UncheckedIOException("列举对象失败", e);
        }
        return keys;
    }

    public void deleteKey(String key) {
        synchronized (lockOf(key)) {
            try {
                Files.deleteIfExists(metaFile(key));
            } catch (IOException e) {
                throw new UncheckedIOException("删除对象元数据失败: " + key, e);
            }
        }
    }

    public ObjectVersion findVersion(ObjectMeta meta, String versionId) {
        return meta == null ? null : meta.findVersion(versionId);
    }
}
