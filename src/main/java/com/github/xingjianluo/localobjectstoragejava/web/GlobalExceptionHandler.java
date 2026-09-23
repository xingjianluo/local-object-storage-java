package com.github.xingjianluo.localobjectstoragejava.web;

import com.github.xingjianluo.localobjectstoragejava.model.ErrorCode;
import com.github.xingjianluo.localobjectstoragejava.model.StorageException;
import com.github.xingjianluo.localobjectstoragejava.web.dto.Dtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.UncheckedIOException;
import java.util.Map;

/**
 * 把业务异常映射为带可区分 errorCode 的 JSON 响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 客户端类失败 -> 4xx；数据/对象缺失 -> 404；冲突/状态不符 -> 409。 */
    private static final Map<ErrorCode, HttpStatus> STATUS = Map.ofEntries(
            Map.entry(ErrorCode.SESSION_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.OBJECT_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.VERSION_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.SESSION_EXPIRED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.SESSION_COMPLETED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.SESSION_ABORTED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.SESSION_ALREADY_COMPLETED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.PART_NUMBER_OUT_OF_RANGE, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.PART_SIZE_MISMATCH, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.PART_CHECKSUM_MISMATCH, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.PART_CONFLICT, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.PART_MISSING, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.TOTAL_CHECKSUM_MISMATCH, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.VERSION_DELETED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.OBJECT_DELETED, HttpStatus.CONFLICT)
    );

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleStorage(StorageException ex) {
        HttpStatus status = STATUS.getOrDefault(ex.getErrorCode(), HttpStatus.UNPROCESSABLE_ENTITY);
        log.warn("业务失败 code={} status={} msg={}", ex.getErrorCode(), status, ex.getMessage());
        return ResponseEntity.status(status)
                .body(new Dtos.ErrorResponse(ex.getErrorCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleIo(UncheckedIOException ex) {
        log.error("存储 IO 失败", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Dtos.ErrorResponse("STORAGE_IO_ERROR", ex.getMessage()));
    }
}
