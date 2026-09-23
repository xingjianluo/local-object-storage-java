package com.github.xingjianluo.localobjectstoragejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地对象存储可配置项，前缀 storage。
 */
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    private String rootDir = "data/storage";
    private long sessionTtlMillis = 24L * 60 * 60 * 1000;
    private long reapIntervalMillis = 5L * 60 * 1000;
    private int copyBufferSize = 64 * 1024;
    private long blobGcGraceMillis = 60L * 1000;

    public String getRootDir() { return rootDir; }
    public void setRootDir(String rootDir) { this.rootDir = rootDir; }

    public long getSessionTtlMillis() { return sessionTtlMillis; }
    public void setSessionTtlMillis(long sessionTtlMillis) { this.sessionTtlMillis = sessionTtlMillis; }

    public long getReapIntervalMillis() { return reapIntervalMillis; }
    public void setReapIntervalMillis(long reapIntervalMillis) { this.reapIntervalMillis = reapIntervalMillis; }

    public int getCopyBufferSize() { return copyBufferSize; }
    public void setCopyBufferSize(int copyBufferSize) { this.copyBufferSize = copyBufferSize; }

    public long getBlobGcGraceMillis() { return blobGcGraceMillis; }
    public void setBlobGcGraceMillis(long blobGcGraceMillis) { this.blobGcGraceMillis = blobGcGraceMillis; }
}
