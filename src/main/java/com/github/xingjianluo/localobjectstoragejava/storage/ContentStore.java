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

    /**
     * 对账报告。
     *
     * @param removedOrphans    已删除的无引用残留内容
     * @param missingContents   元数据引用但底层缺失的内容（数据损坏，需人工介入）
     * @param correctedRefCounts 引用计数被修正的内容
     * @param removedTmpFiles   已清理的临时文件数
     */
    record ReconcileReport(java.util.List<String> removedOrphans,
                           java.util.List<String> missingContents,
                           java.util.List<String> correctedRefCounts,
                           int removedTmpFiles) {}

    /**
     * 启动对账：以 {@code expectedRefs}（contentId -> 应有引用数，来自版本链元数据）为准，
     * 修正引用计数漂移、删除不被引用的残留内容、清理中断遗留的临时文件。
     * 被引用的内容绝不删除；被引用但缺失的内容记入报告而不静默。
     */
    ReconcileReport reconcile(Map<String, Integer> expectedRefs);
}
