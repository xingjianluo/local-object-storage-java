package com.github.xingjianluo.localobjectstoragejava.model;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 分片上传会话。
 *
 * <p>会话状态机：INIT -> COMPLETED 或 INIT -> ABORTED，均为终态。
 * 已上传分片保存在按编号排序的 Map 中，允许乱序上传。</p>
 */
public class UploadSession {

    public enum State { INIT, COMPLETED, ABORTED }

    private String sessionId;
    private String objectKey;
    private int totalParts;
    private long partSize;
    private Map<Integer, PartInfo> parts = new TreeMap<>();
    private State state = State.INIT;
    private long createdAtMillis;
    private long expiresAtMillis;
    private String completedVersionId;
    private String contentId;
    private long totalSize;
    private String totalChecksum;

    public UploadSession() {
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public int getTotalParts() {
        return totalParts;
    }

    public void setTotalParts(int totalParts) {
        this.totalParts = totalParts;
    }

    public long getPartSize() {
        return partSize;
    }

    public void setPartSize(long partSize) {
        this.partSize = partSize;
    }

    public Map<Integer, PartInfo> getParts() {
        if (parts == null) {
            parts = new TreeMap<>();
        }
        return parts;
    }

    public void setParts(Map<Integer, PartInfo> parts) {
        this.parts = parts == null ? new TreeMap<>() : new TreeMap<>(parts);
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public void setCreatedAtMillis(long createdAtMillis) {
        this.createdAtMillis = createdAtMillis;
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    public void setExpiresAtMillis(long expiresAtMillis) {
        this.expiresAtMillis = expiresAtMillis;
    }

    public String getCompletedVersionId() {
        return completedVersionId;
    }

    public void setCompletedVersionId(String completedVersionId) {
        this.completedVersionId = completedVersionId;
    }

    public String getContentId() {
        return contentId;
    }

    public void setContentId(String contentId) {
        this.contentId = contentId;
    }

    public long getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(long totalSize) {
        this.totalSize = totalSize;
    }

    public String getTotalChecksum() {
        return totalChecksum;
    }

    public void setTotalChecksum(String totalChecksum) {
        this.totalChecksum = totalChecksum;
    }

    public boolean isExpired(long nowMillis) {
        return state == State.INIT && nowMillis >= expiresAtMillis;
    }

    public Map<Integer, PartInfo> immutableParts() {
        return Collections.unmodifiableMap(getParts());
    }
}
