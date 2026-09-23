package com.github.xingjianluo.localobjectstoragejava.store;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性回收过期会话与不可达内容。
 */
@Component
public class MaintenanceTask {

    private final Reaper reaper;

    public MaintenanceTask(Reaper reaper) {
        this.reaper = reaper;
    }

    @Scheduled(fixedDelayString = "${storage.reap-interval-millis:300000}")
    public void run() {
    }
}
