package com.github.xingjianluo.localobjectstoragejava.web.dto;

/**
 * REST 请求/响应载体。
 */
public final class Dtos {

    private Dtos() {
    }

    public record CreateSessionRequest(String objectKey, Integer totalParts, Long partSize) {
    }

    public record SessionResponse() {
    }

    public record CompleteRequest(String checksum) {
    }

    public record VersionResponse() {
    }

    public record RollbackRequest(String versionId) {
    }

    public record ErrorResponse(String errorCode, String message) {
    }

    public record ReapResponse(int expiredSessions, int unreachableBlobs) {
    }
}
