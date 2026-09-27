package com.github.xingjianluo.localobjectstoragejava.storage;

import java.io.InputStream;
import java.util.List;

/**
 * 对象版本存储：版本链、按版本读取/删除/回滚。
 */
public interface ObjectStorageService {

    record VersionInfo(String versionId, String objectKey, String contentId,
                       long size, long createdAtEpochMillis, boolean deleted) {}

    record ObjectData(VersionInfo version, InputStream stream) {}

    /**
     * 范围读取结果。
     *
     * @param version    实际读到的版本信息
     * @param totalSize  对象总字节数
     * @param rangeStart 本次实际返回的起始偏移（含）
     * @param rangeEnd   本次实际返回的结束偏移（含）
     * @param hasMore    该偏移之后是否还有数据（rangeEnd < totalSize - 1）
     * @param stream     仅覆盖 [rangeStart, rangeEnd] 的流，需由调用方关闭
     */
    record RangeData(VersionInfo version, long totalSize, long rangeStart, long rangeEnd,
                     boolean hasMore, InputStream stream) {

        /** 本次实际返回的字节数。 */
        public long rangeLength() {
            return rangeEnd - rangeStart + 1;
        }
    }

    /** 提交一个新版本（由上传会话合并后调用），返回版本ID。 */
    String commitVersion(String objectKey, String contentId, long size);

    /** 读取最新版本。 */
    ObjectData readLatest(String objectKey);

    /** 按版本ID读取。 */
    ObjectData readVersion(String objectKey, String versionId);

    /**
     * 按字节范围读取最新版本。
     *
     * @param start 起始偏移（含），从 0 开始
     * @param end   结束偏移（含）；超出对象末尾时截断到末尾
     * @throws StorageException {@code INVALID_RANGE} 范围本身不合法（负值、end < start）；
     *                          {@code RANGE_NOT_SATISFIABLE} 起始偏移已超出对象长度；
     *                          {@code OBJECT_NOT_FOUND} 对象不存在
     */
    RangeData readRangeLatest(String objectKey, long start, long end);

    /**
     * 按字节范围读取指定历史版本，语义同 {@link #readRangeLatest}。
     *
     * @throws StorageException {@code VERSION_NOT_FOUND} 版本不存在
     */
    RangeData readRangeVersion(String objectKey, String versionId, long start, long end);

    /** 列出对象全部版本（新到旧）。 */
    List<VersionInfo> listVersions(String objectKey);

    /** 删除指定版本。 */
    void deleteVersion(String objectKey, String versionId);

    /** 删除整个对象（全部版本）。 */
    void deleteObject(String objectKey);

    /** 回滚：以历史版本内容生成一个新版本，返回新版本ID。 */
    String rollback(String objectKey, String versionId);

    /**
     * 全部存活版本对内容的引用快照（contentId -> 引用次数），供启动对账使用。
     */
    java.util.Map<String, Integer> contentReferences();
}
