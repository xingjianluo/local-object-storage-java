package com.github.xingjianluo.localobjectstoragejava.web.dto;

import java.util.List;

/**
 * REST 请求/响应载体。
 */
public final class Dtos {

    private Dtos() {
    }

    public record CreateSessionRequest(String objectKey, Integer totalParts, Long partSize) {
    }

    public record PartInfoResponse(int number, long size, String checksum, long uploadedAtMillis) {
    }

    public record SessionResponse(String sessionId, String objectKey, int totalParts, long partSize,
                                  String state, long createdAtMillis, long expiresAtMillis,
                                  List<PartInfoResponse> parts,
                                  String completedVersionId, String contentId) {
    }

    public record CompleteRequest(String checksum) {
    }

    public record VersionResponse(String versionId, String objectKey, String contentId, long size,
                                  String checksum, long createdAtMillis, boolean deleted,
                                  String originVersionId) {
    }

    public record ObjectMetaResponse(String key, String currentVersionId,
                                     List<VersionResponse> versions) {
    }

    public record RollbackRequest(String versionId) {
    }

    public record ErrorResponse(String errorCode, String message) {
    }

    public record ReapResponse(int expiredSessions, int unreachableBlobs) {
    }
}
