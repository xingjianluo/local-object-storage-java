package com.github.xingjianluo.localobjectstoragejava.store;

import com.github.xingjianluo.localobjectstoragejava.model.ObjectMeta;
import com.github.xingjianluo.localobjectstoragejava.model.ObjectVersion;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 对象元数据存储：以对象 key 为单位维护追加式版本链与当前版本指针。
 * 每个 key 一个 JSON 文件，原子写入。
 */
@org.springframework.stereotype.Component
public class MetadataStore {

    public ObjectMeta load(String key) {
        return null;
    }

    public ObjectMeta mutate(String key, UnaryOperator<ObjectMeta> mutation) {
        return null;
    }

    public List<String> listKeys() {
        return List.of();
    }

    public void deleteKey(String key) {
    }

    public ObjectVersion findVersion(ObjectMeta meta, String versionId) {
        return null;
    }
}
