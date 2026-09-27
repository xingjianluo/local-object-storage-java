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
     * 打开内容的字节区间 [offset, offset+length) 读取流，流式读取、内存占用与区间大小无关。
     * 内容不存在时抛出 CONTENT_MISSING；调用方需保证区间落在内容长度之内。
     */
    InputStream openRange(String contentId, long offset, long length);

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

    /**
     * 启动对账（进程启动时、无任何并发读写的前提下调用）：
     * 以版本链实际引用为准校正全部引用计数并持久化——崩溃可能留下
     * “已落盘但未计数”或“已计数但无版本引用”的残留，统一校正后由
     * {@link #sweep()} 回收零引用内容；同时清理写入中断遗留的临时文件。
     *
     * @param expectedRefCounts 版本链统计出的 contentId -> 引用次数
     */
    void reconcile(Map<String, Integer> expectedRefCounts);
}
