package com.github.xingjianluo.localobjectstoragejava.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性回收过期会话与不可达内容。
 * fixedDelay：上一次执行结束后再计时，避免回收任务重叠。
 */
@Component
public class MaintenanceTask {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceTask.class);

    private final Reaper reaper;

    public MaintenanceTask(Reaper reaper) {
        this.reaper = reaper;
    }

    @Scheduled(fixedDelayString = "${storage.reap-interval-millis:300000}",
            initialDelayString = "${storage.reap-interval-millis:300000}")
    public void run() {
        try {
            long now = System.currentTimeMillis();
            int sessions = reaper.reapExpiredSessions(now);
            int blobs = reaper.reapUnreachableBlobs(now);
            if (sessions > 0 || blobs > 0) {
                log.info("定时回收: 过期会话={} 不可达内容={}", sessions, blobs);
            }
        } catch (Exception e) {
            log.error("定时回收失败", e);
        }
    }
}
