package com.github.xingjianluo.localobjectstoragejava.service;

import com.github.xingjianluo.localobjectstoragejava.model.ErrorCode;
import com.github.xingjianluo.localobjectstoragejava.model.ObjectMeta;
import com.github.xingjianluo.localobjectstoragejava.model.ObjectVersion;
import com.github.xingjianluo.localobjectstoragejava.model.StorageException;
import com.github.xingjianluo.localobjectstoragejava.store.BlobStore;
import com.github.xingjianluo.localobjectstoragejava.store.MetadataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 对象读取、版本列举、版本删除与回滚。
 *
 * <p>并发语义：所有版本链变更串行于对象 key 锁；读取先在 key 锁内确定
 * “当前/指定版本 -> contentId”，随后 blob 读取由 {@link BlobStore} 的活动读者
 * 计数保护。因此“删除与回滚交错”时，每个读者要么打开删除前的完整内容，
 * 要么在打开时得到明确失败，不会读到半成品。</p>
 *
 * <p>删除语义（可解释）：</p>
 * <ul>
 *   <li>删除当前版本：若存在其它未删除历史版本，当前指针回退到最近一个；
 *       否则置 null，读取得到 OBJECT_DELETED。</li>
 *   <li>删除历史版本：仅释放该版本引用，不影响当前版本。</li>
 *   <li>回滚：不复活旧记录，而是追加一个指向历史内容的新版本，
 *       同内容通过 contentId 去重，引用计数 +1，版本链始终追加不回绕。</li>
 * </ul>
 */
@Service
public class ObjectService {

    private static final Logger log = LoggerFactory.getLogger(ObjectService.class);

    private final MetadataStore metadataStore;
    private final BlobStore blobStore;

    public ObjectService(MetadataStore metadataStore, BlobStore blobStore) {
        this.metadataStore = metadataStore;
        this.blobStore = blobStore;
    }

    /**
     * 读取当前版本内容。调用方负责关闭返回的通道。
     */
    public ReadableByteChannel readCurrent(String key) {
        return metadataStore.withKeyLock(key, meta -> {
            if (meta == null) {
                throw new StorageException(ErrorCode.OBJECT_NOT_FOUND, "对象不存在: " + key);
            }
            ObjectVersion current = meta.currentVersion();
            if (current == null) {
                throw new StorageException(ErrorCode.OBJECT_DELETED, "对象没有可读的当前版本: " + key);
            }
            // 活动读者登记发生在 blob 锁内；此时仍持有 key 锁，
            // 与“删除时 key 锁内 releaseRef”互斥：要么先登记读者，要么先释放引用。
            return blobStore.openChannel(current.getContentId());
        });
    }

    /**
     * 读取指定历史版本。
     */
    public ReadableByteChannel readVersion(String key, String versionId) {
        return metadataStore.withKeyLock(key, meta -> {
            ObjectVersion version = requireLiveVersion(meta, key, versionId);
            return blobStore.openChannel(version.getContentId());
        });
    }

    /**
     * 版本元数据（版本链、删除标记与当前指针）。
     */
    public ObjectMeta listVersions(String key) {
        ObjectMeta meta = metadataStore.load(key);
        if (meta == null) {
            throw new StorageException(ErrorCode.OBJECT_NOT_FOUND, "对象不存在: " + key);
        }
        return meta;
    }

    /**
     * 删除指定版本。释放引用与删除标记在同一把 key 锁、同一次原子落盘生效。
     */
    public void deleteVersion(String key, String versionId) {
        metadataStore.mutate(key, meta -> {
            if (meta.getVersions().isEmpty()) {
                throw new StorageException(ErrorCode.OBJECT_NOT_FOUND, "对象不存在: " + key);
            }
            ObjectVersion version = meta.findVersion(versionId);
            if (version == null) {
                throw new StorageException(ErrorCode.VERSION_NOT_FOUND,
                        "版本不存在: key=" + key + " versionId=" + versionId);
            }
            if (version.isDeleted()) {
                throw new StorageException(ErrorCode.VERSION_DELETED,
                        "版本已删除，不能重复删除: " + versionId);
            }
            blobStore.releaseRef(version.getContentId(), key, versionId);
            version.setDeleted(true);
            version.setDeletedAtMillis(System.currentTimeMillis());

            if (versionId.equals(meta.getCurrentVersionId())) {
                ObjectVersion fallback = latestLiveVersion(meta);
                meta.setCurrentVersionId(fallback == null ? null : fallback.getVersionId());
                log.info("删除当前版本 key={} versionId={} contentId={} fallback={}",
                        key, versionId, version.getContentId(),
                        fallback == null ? "none" : fallback.getVersionId());
            } else {
                log.info("删除历史版本 key={} versionId={} contentId={}",
                        key, versionId, version.getContentId());
            }
            return meta;
        });
    }

    /**
     * 回滚到指定历史版本：追加一个内容相同的新版本（去重复用 blob，引用 +1）。
     */
    public ObjectVersion rollback(String key, String versionId) {
        ObjectVersion[] holder = new ObjectVersion[1];
        metadataStore.mutate(key, meta -> {
            if (meta.getVersions().isEmpty()) {
                throw new StorageException(ErrorCode.OBJECT_NOT_FOUND, "对象不存在: " + key);
            }
            ObjectVersion target = meta.findVersion(versionId);
            if (target == null) {
                throw new StorageException(ErrorCode.VERSION_NOT_FOUND,
                        "版本不存在: key=" + key + " versionId=" + versionId);
            }
            if (target.isDeleted()) {
                throw new StorageException(ErrorCode.VERSION_DELETED,
                        "不能回滚到已删除版本: " + versionId);
            }
            String newVersionId = "v" + UUID.randomUUID().toString().replace("-", "");
            ObjectVersion copy = new ObjectVersion();
            copy.setVersionId(newVersionId);
            copy.setObjectKey(key);
            copy.setContentId(target.getContentId());
            copy.setSize(target.getSize());
            copy.setChecksum(target.getChecksum());
            copy.setCreatedAtMillis(System.currentTimeMillis());
            copy.setOriginVersionId(versionId);
            blobStore.acquireRef(target.getContentId(), key, newVersionId);
            meta.getVersions().add(copy);
            meta.setCurrentVersionId(newVersionId);
            holder[0] = copy;
            log.info("回滚 key={} from={} newVersion={} contentId={} refCount={}",
                    key, versionId, newVersionId, target.getContentId(),
                    blobStore.countRefs(target.getContentId()));
            return meta;
        });
        return holder[0];
    }

    private ObjectVersion requireLiveVersion(ObjectMeta meta, String key, String versionId) {
        if (meta == null) {
            throw new StorageException(ErrorCode.OBJECT_NOT_FOUND, "对象不存在: " + key);
        }
        ObjectVersion version = meta.findVersion(versionId);
        if (version == null) {
            throw new StorageException(ErrorCode.VERSION_NOT_FOUND,
                    "版本不存在: key=" + key + " versionId=" + versionId);
        }
        if (version.isDeleted()) {
            throw new StorageException(ErrorCode.VERSION_DELETED, "版本已删除: " + versionId);
        }
        return version;
    }

    private ObjectVersion latestLiveVersion(ObjectMeta meta) {
        ObjectVersion latest = null;
        for (ObjectVersion v : meta.getVersions()) {
            if (!v.isDeleted()) {
                latest = v;
            }
        }
        return latest;
    }
}
