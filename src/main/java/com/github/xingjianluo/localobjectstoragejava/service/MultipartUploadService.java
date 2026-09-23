package com.github.xingjianluo.localobjectstoragejava.service;

import com.github.xingjianluo.localobjectstoragejava.model.UploadSession;
import com.github.xingjianluo.localobjectstoragejava.store.BlobStore;
import com.github.xingjianluo.localobjectstoragejava.store.MetadataStore;
import com.github.xingjianluo.localobjectstoragejava.store.SessionStore;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * 分片上传协议：创建会话 -> 乱序/重复上传分片 -> 校验合并 -> 对象版本原子可见。
 */
@Service
public class MultipartUploadService {

    private final SessionStore sessionStore;
    private final BlobStore blobStore;
    private final MetadataStore metadataStore;

    public MultipartUploadService(SessionStore sessionStore, BlobStore blobStore, MetadataStore metadataStore) {
        this.sessionStore = sessionStore;
        this.blobStore = blobStore;
        this.metadataStore = metadataStore;
    }

    public UploadSession createSession(String objectKey, int totalParts, long partSize) {
        return null;
    }

    public UploadSession uploadPart(String sessionId, int partNumber, long expectedSize,
                                   String expectedChecksum, InputStream content) {
        return null;
    }

    public UploadSession completeSession(String sessionId, String expectedChecksum) {
        return null;
    }

    public void abortSession(String sessionId) {
    }

    public UploadSession getSession(String sessionId) {
        return null;
    }
}
