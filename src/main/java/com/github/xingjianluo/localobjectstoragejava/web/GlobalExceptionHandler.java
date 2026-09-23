package com.github.xingjianluo.localobjectstoragejava.web;

import com.github.xingjianluo.localobjectstoragejava.model.StorageException;
import com.github.xingjianluo.localobjectstoragejava.web.dto.Dtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把业务异常映射为带可区分 errorCode 的 JSON 响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleStorage(StorageException ex) {
        return null;
    }
}
