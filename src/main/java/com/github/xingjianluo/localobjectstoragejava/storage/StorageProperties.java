package com.github.xingjianluo.localobjectstoragejava.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 本地对象存储可配置项。
 */
@ConfigurationProperties(prefix = "local-storage")
public class StorageProperties {

    /** 存储根目录 */
    private String rootDir = "./data/storage";

    /** 上传会话过期时间 */
    private Duration sessionTtl = Duration.ofMinutes(30);

    /** 回收任务执行间隔 */
    private Duration gcInterval = Duration.ofMinutes(5);

    /** 单个分片最大字节数 */
    private long maxPartSize = 64L * 1024 * 1024;

    public String getRootDir() {
        return rootDir;
    }

    public void setRootDir(String rootDir) {
        this.rootDir = rootDir;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public Duration getGcInterval() {
        return gcInterval;
    }

    public void setGcInterval(Duration gcInterval) {
        this.gcInterval = gcInterval;
    }

    public long getMaxPartSize() {
        return maxPartSize;
    }

    public void setMaxPartSize(long maxPartSize) {
        this.maxPartSize = maxPartSize;
    }
}
