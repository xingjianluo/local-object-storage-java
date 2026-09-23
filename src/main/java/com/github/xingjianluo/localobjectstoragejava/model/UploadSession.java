package com.github.xingjianluo.localobjectstoragejava.model;

import java.util.Map;

/**
 * 分片上传会话。
 */
public class UploadSession {

    public enum State { INIT, COMPLETED, ABORTED }

    public String getSessionId() { return null; }

    public String getObjectKey() { return null; }

    public int getTotalParts() { return 0; }

    public long getPartSize() { return 0; }

    public Map<Integer, PartInfo> getParts() { return Map.of(); }

    public State getState() { return State.INIT; }

    public long getCreatedAtMillis() { return 0; }

    public long getExpiresAtMillis() { return 0; }
}
