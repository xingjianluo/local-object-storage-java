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
        /** 范围写法本身非法（负偏移、零/负长度、起止颠倒、Range 头无法解析），HTTP 400。 */
        INVALID_RANGE,
        /** 范围写法合法，但起始位置已达到或超过对象长度（空对象的任何范围同理），HTTP 416。 */
        RANGE_NOT_SATISFIABLE,
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
