package com.github.xingjianluo.localobjectstoragejava.storage;

import java.io.InputStream;
import java.util.List;

/**
 * 对象版本存储：版本链、按版本读取/删除/回滚。
 *
 * <p>整包读取与按段读取返回同一种结果 {@link RangeData}：整包时
 * {@code offset=0, length=totalSize, hasMore=false}，最新版本与历史版本语义一致。</p>
 */
public interface ObjectStorageService {

    record VersionInfo(String versionId, String objectKey, String contentId,
                       long size, long createdAtEpochMillis, boolean deleted) {}

    /**
     * 读取结果（整包与按段共用）：
     * <ul>
     *   <li>{@code totalSize}：对象（该版本）的总字节长度；</li>
     *   <li>{@code [offset, offset+length)}：本次实际返回的字节区间（已做末尾截断）；</li>
     *   <li>{@code hasMore}：本次区间之后是否还有数据（{@code offset + length < totalSize}）；</li>
     *   <li>{@code stream}：仅覆盖本次区间的定界流式输入。</li>
     * </ul>
     */
    record RangeData(VersionInfo version, long totalSize, long offset, long length,
                     boolean hasMore, InputStream stream) {}

    /** 提交一个新版本（由上传会话合并后调用），返回版本ID。 */
    String commitVersion(String objectKey, String contentId, long size);

    /**
     * 读取最新版本整包内容。返回的 {@link RangeData} 中
     * {@code offset=0, length=totalSize, hasMore=false}。
     */
    RangeData readLatest(String objectKey);

    /** 读取指定历史版本整包内容，语义同 {@link #readLatest}。 */
    RangeData readVersion(String objectKey, String versionId);

    /**
     * 按字节范围读取最新版本。
     *
     * <p>范围“写法”非法（{@code offset < 0} 或 {@code length <= 0}）时抛
     * {@link StorageException.ErrorCode#INVALID_RANGE}；写法合法但起始位置达到或超过
     * 对象长度时抛 {@link StorageException.ErrorCode#RANGE_NOT_SATISFIABLE}；
     * 请求长度超出对象末尾时自动截断。</p>
     */
    RangeData readLatestRange(String objectKey, long offset, long length);

    /** 按字节范围读取指定历史版本，语义同 {@link #readLatestRange}。 */
    RangeData readVersionRange(String objectKey, String versionId, long offset, long length);

    /**
     * 按 {@link ByteRange} 读取最新版本。版本解析、范围解析与内容加钉在同一把对象锁内
     * 原子完成，不会读到“版本 A 的长度 + 版本 B 的内容”这类错位组合。
     */
    RangeData readLatestRange(String objectKey, ByteRange range);

    /** 按 {@link ByteRange} 读取指定历史版本，语义同 {@link #readLatestRange(String, ByteRange)}。 */
    RangeData readVersionRange(String objectKey, String versionId, ByteRange range);

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
