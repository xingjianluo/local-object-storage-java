package com.github.xingjianluo.localobjectstoragejava.storage;

/**
 * 存储层统一异常，携带可区分的错误码。
 */
public class StorageException extends RuntimeException {

    public enum ErrorCode {
        SESSION_NOT_FOUND,
        SESSION_EXPIRED,
        SESSION_ALREADY_COMPLETED,
        SESSION_ALREADY_ABORTED,
        PART_NUMBER_OUT_OF_RANGE,
        PART_CHECKSUM_MISMATCH,
        PART_SIZE_MISMATCH,
        PARTS_MISSING,
        OBJECT_NOT_FOUND,
        VERSION_NOT_FOUND,
        CONTENT_MISSING,
        STORAGE_CORRUPT
    }

    private final ErrorCode code;

    public StorageException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
