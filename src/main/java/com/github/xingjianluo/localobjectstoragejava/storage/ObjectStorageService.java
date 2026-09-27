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
     * 范围读取结果：totalSize 为对象总长度，[offset, offset+length) 为本次实际返回的区间，
     * hasMore 表示区间之后是否还有数据。
     */
    record RangeData(VersionInfo version, long totalSize, long offset, long length,
                     boolean hasMore, InputStream stream) {}

    /** 提交一个新版本（由上传会话合并后调用），返回版本ID。 */
    String commitVersion(String objectKey, String contentId, long size);

    /** 读取最新版本。 */
    ObjectData readLatest(String objectKey);

    /** 按版本ID读取。 */
    ObjectData readVersion(String objectKey, String versionId);

    /**
     * 按字节范围读取最新版本：[offset, offset+length)，超出末尾的部分自动截断。
     * 范围非法（负偏移、非正长度）或起始位置超出对象长度时抛出 RANGE_NOT_SATISFIABLE。
     */
    RangeData readLatestRange(String objectKey, long offset, long length);

    /** 按字节范围读取指定历史版本，语义同 {@link #readLatestRange}。 */
    RangeData readVersionRange(String objectKey, String versionId, long offset, long length);

    /** 最新版本的元信息（不打开数据流）。 */
    VersionInfo statLatest(String objectKey);

    /** 指定版本的元信息（不打开数据流）。 */
    VersionInfo statVersion(String objectKey, String versionId);

    /** 版本链对各内容的引用计数（contentId -> 引用次数），用于启动对账。 */
    java.util.Map<String, Integer> contentReferences();

    /** 列出对象全部版本（新到旧）。 */
    List<VersionInfo> listVersions(String objectKey);

    /** 删除指定版本。 */
    void deleteVersion(String objectKey, String versionId);

    /** 删除整个对象（全部版本）。 */
    void deleteObject(String objectKey);

    /** 回滚：以历史版本内容生成一个新版本，返回新版本ID。 */
    String rollback(String objectKey, String versionId);
}
