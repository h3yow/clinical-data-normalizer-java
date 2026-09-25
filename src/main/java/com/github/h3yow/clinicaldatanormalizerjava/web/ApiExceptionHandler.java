package com.github.h3yow.clinicaldatanormalizerjava.web;

import com.github.h3yow.clinicaldatanormalizerjava.auth.AuthorizationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 把领域异常映射为 HTTP 响应；错误原因枚举名直接暴露给调用方以便区分处理。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(AuthorizationException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "status", "REJECTED",
                "errorStage", "AUTHORIZE",
                "errorReason", e.getReason().name(),
                "errorMessage", e.getMessage() == null ? "" : e.getMessage()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", "REJECTED",
                "errorReason", "BAD_REQUEST",
                "errorMessage", e.getMessage() == null ? "" : e.getMessage()
        ));
    }
}
