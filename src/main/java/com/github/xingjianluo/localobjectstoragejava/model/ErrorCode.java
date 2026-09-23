package com.github.xingjianluo.localobjectstoragejava.model;

/**
 * 业务失败原因。每种失败使用唯一 code，保证调用方可以区分处理。
 */
public enum ErrorCode {
    SESSION_NOT_FOUND,
    SESSION_EXPIRED,
    SESSION_COMPLETED,
    SESSION_ABORTED,
    PART_NUMBER_OUT_OF_RANGE,
    PART_SIZE_MISMATCH,
    PART_CHECKSUM_MISMATCH,
    PART_CONFLICT,
    PART_MISSING,
    TOTAL_CHECKSUM_MISMATCH,
    OBJECT_NOT_FOUND,
    VERSION_NOT_FOUND,
    VERSION_DELETED,
    OBJECT_DELETED,
    SESSION_ALREADY_COMPLETED
}
