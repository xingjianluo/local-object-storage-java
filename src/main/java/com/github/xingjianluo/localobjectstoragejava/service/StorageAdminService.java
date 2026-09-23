package com.github.xingjianluo.localobjectstoragejava.service;

import com.github.xingjianluo.localobjectstoragejava.store.Reaper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 管理操作：手动触发一次完整回收，供验证与测试使用。
 */
@Service
public class StorageAdminService {

    private static final Logger log = LoggerFactory.getLogger(StorageAdminService.class);

    private final Reaper reaper;

    public StorageAdminService(Reaper reaper) {
        this.reaper = reaper;
    }

    /**
     * @return [过期会话清理数, 物理删除 blob 数]
     */
    public int[] reapNow() {
        long now = System.currentTimeMillis();
        int[] result = reaper.reapAll(now);
        log.info("手动回收完成 expiredSessions={} unreachableBlobs={}", result[0], result[1]);
        return result;
    }
}
