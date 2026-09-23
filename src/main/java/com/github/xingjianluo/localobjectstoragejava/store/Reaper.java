package com.github.xingjianluo.localobjectstoragejava.store;

import com.github.xingjianluo.localobjectstoragejava.model.ObjectMeta;
import com.github.xingjianluo.localobjectstoragejava.model.ObjectVersion;
import com.github.xingjianluo.localobjectstoragejava.model.UploadSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资源回收器：
 * <ol>
 *   <li>删除已过期的 INIT 上传会话（分片目录连同清理），中断的上传不产生可见对象；</li>
 *   <li>以对象元数据为权威对账 refs 标记：补回丢失的引用标记、清除多余标记；</li>
 *   <li>删除零引用、超过宽限期且无活动读者的 blob。</li>
 * </ol>
 * 回收以磁盘与元数据为事实来源，服务重启后仍可正确工作。
 */
@Component
public class Reaper {

    private static final Logger log = LoggerFactory.getLogger(Reaper.class);

    /** 单次回收等待活动读者退出的最长时间。 */
    private static final long WAIT_FOR_READERS_MILLIS = 5_000L;

    private final SessionStore sessionStore;
    private final MetadataStore metadataStore;
    private final BlobStore blobStore;

    public Reaper(SessionStore sessionStore, MetadataStore metadataStore, BlobStore blobStore) {
        this.sessionStore = sessionStore;
        this.metadataStore = metadataStore;
        this.blobStore = blobStore;
    }

    /**
     * 回收过期会话：
     * <ul>
     *   <li>INIT 且过期：中断的上传从未产生可见对象，整体清理分片；</li>
     *   <li>COMPLETED/ABORTED 终态：保留至过期后再清理 session.json，
     *       期间重复请求仍能拿到可区分的失败原因。</li>
     * </ul>
     */
    public int reapExpiredSessions(long nowMillis) {
        int removed = 0;
        for (UploadSession session : sessionStore.listSessions()) {
            boolean expired = nowMillis >= session.getExpiresAtMillis();
            if (!expired) {
                continue;
            }
            if (session.getState() == UploadSession.State.INIT) {
                log.info("回收过期未完成会话 sid={} key={} expiresAt={} (未产生可见对象)",
                        session.getSessionId(), session.getObjectKey(), session.getExpiresAtMillis());
            } else {
                log.info("清理终态会话 sid={} state={} key={}",
                        session.getSessionId(), session.getState(), session.getObjectKey());
            }
            sessionStore.removeSession(session.getSessionId());
            removed++;
        }
        return removed;
    }

    /**
     * 对账并回收不可达 blob。
     *
     * @return 物理删除的 blob 数量
     */
    public int reapUnreachableBlobs(long nowMillis) {
        List<String> keys = metadataStore.listKeys();
        Map<String, Integer> expectedRefCounts = new HashMap<>();
        for (String key : keys) {
            ObjectMeta meta = metadataStore.load(key);
            if (meta == null) {
                continue;
            }
            for (ObjectVersion v : meta.getVersions()) {
                if (!v.isDeleted()) {
                    expectedRefCounts.merge(v.getContentId(), 1, Integer::sum);
                }
            }
        }
        Set<String> onDisk = blobStore.listContentIds();
        // 对账：磁盘有 blob 但元数据完全不引用 -> 不可达候选。
        Set<String> unreachable = blobStore.reconcileAndFindUnreachable(
                keys.stream().map(metadataStore::load).filter(m -> m != null).toList());
        for (String id : onDisk) {
            int actual = blobStore.countRefs(id);
            int expected = expectedRefCounts.getOrDefault(id, 0);
            if (actual != expected) {
                log.warn("引用计数对账差异 contentId={} refsOnDisk={} refsExpected={}，以元数据为准",
                        id, actual, expected);
            }
        }
        int deleted = blobStore.deleteUnreachable(unreachable, nowMillis, WAIT_FOR_READERS_MILLIS);
        log.info("不可达内容回收完成 candidates={} deleted={}", unreachable.size(), deleted);
        return deleted;
    }

    /**
     * 一次性执行完整回收周期。
     */
    public int[] reapAll(long nowMillis) {
        int sessions = reapExpiredSessions(nowMillis);
        int blobs = reapUnreachableBlobs(nowMillis);
        return new int[]{sessions, blobs};
    }

}
