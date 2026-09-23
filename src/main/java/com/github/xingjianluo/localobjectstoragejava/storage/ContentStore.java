package com.github.xingjianluo.localobjectstoragejava.storage;

import java.io.InputStream;
import java.util.Map;

/**
 * 内容寻址的底层块存储：相同内容只保留一份，维护引用计数。
 */
public interface ContentStore {

    /**
     * 写入内容（若已存在则仅增加引用），返回内容标识（sha256）。
     */
    String put(InputStream data, long size);

    /**
     * 增加对某内容的引用。
     */
    void retain(String contentId);

    /**
     * 释放对某内容的引用，引用归零后内容可被回收。
     */
    void release(String contentId);

    /**
     * 打开内容读取流；内容不存在时抛出 CONTENT_MISSING。
     */
    InputStream open(String contentId);

    /**
     * 内容字节数；不存在返回 -1。
     */
    long sizeOf(String contentId);

    /**
     * 当前引用计数快照（contentId -> refcount），用于测试与回收。
     */
    Map<String, Integer> refCounts();

    /**
     * 回收引用计数为零且不可达的内容，返回回收的内容标识列表。
     */
    java.util.List<String> sweep();
}
