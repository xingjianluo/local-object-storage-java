package com.github.xingjianluo.localobjectstoragejava.store;

import com.github.xingjianluo.localobjectstoragejava.model.PartInfo;
import com.github.xingjianluo.localobjectstoragejava.model.UploadSession;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 上传会话与分片的磁盘存储。
 * 会话 JSON + 每个分片一个文件，会话变更原子写入。
 */
@org.springframework.stereotype.Component
public class SessionStore {

    public UploadSession create(String objectKey, int totalParts, long partSize, long ttlMillis) {
        return null;
    }

    public UploadSession load(String sessionId) {
        return null;
    }

    public UploadSession mutate(String sessionId, UnaryOperator<UploadSession> mutation) {
        return null;
    }

    public java.nio.file.Path partPath(String sessionId, int partNumber) {
        return null;
    }

    public void removeSession(String sessionId) {
    }

    public List<UploadSession> listSessions() {
        return List.of();
    }

    public PartInfo newPartInfo(int number, long size, String checksum) {
        return null;
    }
}
