package com.chichijik.common;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 모든 오류를 {"error": "메시지"} 형태로 응답합니다. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final String maxUploadSize;

    public GlobalExceptionHandler(@Value("${spring.servlet.multipart.max-file-size}") String maxUploadSize) {
        this.maxUploadSize = maxUploadSize;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> handleApi(ApiException e) {
        return error(e.getStatus(), e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> handleTooLarge() {
        return error(HttpStatus.BAD_REQUEST, "파일은 " + maxUploadSize + " 이하만 올릴 수 있습니다.");
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<Map<String, String>> handleMultipart() {
        return error(HttpStatus.BAD_REQUEST, "업로드 요청을 처리할 수 없습니다.");
    }

    @ExceptionHandler({NoResourceFoundException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> handleNotFound() {
        return error(HttpStatus.NOT_FOUND, "요청한 대상을 찾을 수 없습니다.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, String>> handleMethod() {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> handleUnknown(Exception e) {
        log.error("처리되지 않은 오류", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
