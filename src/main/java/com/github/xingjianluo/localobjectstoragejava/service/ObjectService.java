package com.github.xingjianluo.localobjectstoragejava.service;

import com.github.xingjianluo.localobjectstoragejava.model.ObjectMeta;
import com.github.xingjianluo.localobjectstoragejava.model.ObjectVersion;
import com.github.xingjianluo.localobjectstoragejava.store.BlobStore;
import com.github.xingjianluo.localobjectstoragejava.store.MetadataStore;
import org.springframework.stereotype.Service;

import java.nio.channels.ReadableByteChannel;

/**
 * 对象读取、版本列举、版本删除、版本回滚。
 */
@Service
public class ObjectService {

    private final MetadataStore metadataStore;
    private final BlobStore blobStore;

    public ObjectService(MetadataStore metadataStore, BlobStore blobStore) {
        this.metadataStore = metadataStore;
        this.blobStore = blobStore;
    }

    public ReadableByteChannel readCurrent(String key) {
        return null;
    }

    public ReadableByteChannel readVersion(String key, String versionId) {
        return null;
    }

    public ObjectMeta listVersions(String key) {
        return null;
    }

    public void deleteVersion(String key, String versionId) {
    }

    public ObjectVersion rollback(String key, String versionId) {
        return null;
    }
}
