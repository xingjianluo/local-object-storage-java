package com.github.xingjianluo.localobjectstoragejava.store;

/**
 * 元数据 JSON 序列化（Jackson）。
 */
@org.springframework.stereotype.Component
public class JsonCodec {

    public String toJson(Object value) {
        return null;
    }

    public <T> T fromJson(String json, Class<T> type) {
        return null;
    }
}
