package com.github.xingjianluo.localobjectstoragejava.storage;

import java.io.InputStream;
import java.util.List;

/**
 * 分片上传会话管理：创建会话、乱序/重复分片上传、合并完成、中止与过期回收。
 */
public interface UploadSessionManager {

    record PartSpec(int partNumber, long size, String sha256) {}

    record SessionInfo(String sessionId, String objectKey, int partCount,
                       long expiresAtEpochMillis, boolean completed, boolean aborted,
                       List<Integer> receivedParts) {}

    /** 创建上传会话，返回会话ID。 */
    String createSession(String objectKey, List<PartSpec> parts);

    /** 上传一个分片（可乱序、可重复，重复时内容须一致）。 */
    void uploadPart(String sessionId, int partNumber, InputStream data, long size);

    /** 查询会话状态。 */
    SessionInfo sessionInfo(String sessionId);

    /** 全部分片校验通过后合并并提交为新版本，返回版本ID。 */
    String complete(String sessionId);

    /** 中止会话并清理临时分片。 */
    void abort(String sessionId);

    /** 回收过期会话，返回被回收的会话ID。 */
    List<String> expireSessions();
}
