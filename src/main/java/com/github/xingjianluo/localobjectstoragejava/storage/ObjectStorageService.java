package com.github.xingjianluo.localobjectstoragejava.storage;

import java.io.InputStream;
import java.util.List;

/**
 * 对象版本存储：版本链、按版本读取/删除/回滚。
 */
public interface ObjectStorageService {

    record VersionInfo(String versionId, String objectKey, String contentId,
                       long size, long createdAtEpochMillis, boolean deleted) {}

    /**
     * 整包读取结果。{@code totalSize/offset/length/hasMore} 与 {@link RangeData} 同语义：
     * 整包即区间 [0, size)，故 offset=0、length=totalSize、hasMore=false。
     */
    record ObjectData(VersionInfo version, InputStream stream) {
        public long totalSize() {
            return version.size();
        }

        public long offset() {
            return 0L;
        }

        public long length() {
            return version.size();
        }

        public boolean hasMore() {
            return false;
        }
    }

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
     * 判定顺序：对象/版本不存在分别抛 OBJECT_NOT_FOUND / VERSION_NOT_FOUND；
     * 负偏移抛 INVALID_RANGE；起点达到/超过对象长度（含空对象）抛 RANGE_NOT_SATISFIABLE；
     * 起点合法但长度非正抛 INVALID_RANGE。
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
