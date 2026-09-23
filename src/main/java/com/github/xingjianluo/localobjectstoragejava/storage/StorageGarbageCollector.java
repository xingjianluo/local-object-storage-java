package com.github.xingjianluo.localobjectstoragejava.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定期回收过期上传会话与不可达内容。
 *
 * <p>回收期间读取的安全性由 {@link LocalContentStore} 的读钉（pin）机制保证：
 * 正在被读取的内容不会被删除，读取要么得到完整数据，要么以 CONTENT_MISSING 明确失败。</p>
 */
@Component
public class StorageGarbageCollector {

    private static final Logger log = LoggerFactory.getLogger(StorageGarbageCollector.class);

    private final UploadSessionManager sessionManager;
    private final ContentStore contentStore;

    public StorageGarbageCollector(UploadSessionManager sessionManager, ContentStore contentStore) {
        this.sessionManager = sessionManager;
        this.contentStore = contentStore;
    }

    @Scheduled(fixedDelayString = "${local-storage.gc-interval:PT5M}")
    public void scheduled() {
        runOnce();
    }

    public void runOnce() {
        List<String> expiredSessions = sessionManager.expireSessions();
        List<String> sweptContents = contentStore.sweep();
        if (!expiredSessions.isEmpty() || !sweptContents.isEmpty()) {
            log.info("GC 完成：回收会话 {} 个，回收内容 {} 个",
                    expiredSessions.size(), sweptContents.size());
        }
    }
}
