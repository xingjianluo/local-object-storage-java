package com.github.xingjianluo.localobjectstoragejava.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对象元数据：追加式版本链 + 当前版本指针。
 *
 * <p>不变式：currentVersionId 要么为 null（对象无可见当前版本），
 * 要么指向版本链中一个未删除的版本。</p>
 */
public class ObjectMeta {

    private String key;
    private List<ObjectVersion> versions = new ArrayList<>();
    private String currentVersionId;

    public ObjectMeta() {
    }

    public ObjectMeta(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public List<ObjectVersion> getVersions() {
        if (versions == null) {
            versions = new ArrayList<>();
        }
        return versions;
    }

    public void setVersions(List<ObjectVersion> versions) {
        this.versions = versions == null ? new ArrayList<>() : new ArrayList<>(versions);
    }

    public String getCurrentVersionId() {
        return currentVersionId;
    }

    public void setCurrentVersionId(String currentVersionId) {
        this.currentVersionId = currentVersionId;
    }

    public ObjectVersion findVersion(String versionId) {
        for (ObjectVersion v : getVersions()) {
            if (v.getVersionId().equals(versionId)) {
                return v;
            }
        }
        return null;
    }

    public ObjectVersion currentVersion() {
        return currentVersionId == null ? null : findVersion(currentVersionId);
    }

    public List<ObjectVersion> immutableVersions() {
        return Collections.unmodifiableList(getVersions());
    }
}
