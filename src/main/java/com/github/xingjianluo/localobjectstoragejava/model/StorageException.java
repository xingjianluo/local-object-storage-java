package com.github.xingjianluo.localobjectstoragejava.model;

/**
 * 可区分失败原因的业务异常。
 */
public class StorageException extends RuntimeException {

    private final ErrorCode errorCode;

    public StorageException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
