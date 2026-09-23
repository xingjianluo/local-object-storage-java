package com.github.xingjianluo.localobjectstoragejava.model;

import java.util.List;

/**
 * 对象元数据：版本链 + 当前版本指针。
 */
public class ObjectMeta {

    public String getKey() { return null; }

    public List<ObjectVersion> getVersions() { return List.of(); }

    public String getCurrentVersionId() { return null; }
}
