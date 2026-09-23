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

    /** 提交一个新版本（由上传会话合并后调用），返回版本ID。 */
    String commitVersion(String objectKey, String contentId, long size);

    /** 读取最新版本。 */
    ObjectData readLatest(String objectKey);

    /** 按版本ID读取。 */
    ObjectData readVersion(String objectKey, String versionId);

    /** 列出对象全部版本（新到旧）。 */
    List<VersionInfo> listVersions(String objectKey);

    /** 删除指定版本。 */
    void deleteVersion(String objectKey, String versionId);

    /** 删除整个对象（全部版本）。 */
    void deleteObject(String objectKey);

    /** 回滚：以历史版本内容生成一个新版本，返回新版本ID。 */
    String rollback(String objectKey, String versionId);
}
