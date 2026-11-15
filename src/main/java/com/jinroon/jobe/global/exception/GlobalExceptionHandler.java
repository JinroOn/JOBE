package com.jinroon.jobe.global.exception;

import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.global.exception.error.ErrorDto;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorDto> handleCustomException(CustomException exception) {
        return toResponse(exception.getErrorCode());
    }

    // 다른 오류와 같은 status·code·message 형식에 필드별 메시지(fieldErrors)를 더한다.
    // 이전에는 {"필드명": "메시지"}만 내려 code·message가 없었다.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorDto> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage())
        );
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ErrorDto.withFieldErrors(ErrorCode.INVALID_INPUT, errors));
    }

    // 존재하지 않는 URL. 이전에는 아래 Exception 핸들러에 걸려 500으로 응답했다.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorDto> handleNoResource(NoResourceFoundException exception) {
        return toResponse(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorDto> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return toResponse(ErrorCode.METHOD_NOT_ALLOWED);
    }

    // 읽을 수 없는 JSON 본문, 잘못된 파라미터 타입·누락, 지원하지 않는 Content-Type
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            HttpMediaTypeNotSupportedException.class
    })
    public ResponseEntity<ErrorDto> handleBadRequest(Exception exception) {
        log.debug("Bad request errorType={} message={}", exception.getClass().getSimpleName(), exception.getMessage());
        return toResponse(ErrorCode.INVALID_INPUT);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorDto> handleException(Exception exception) {
        log.error("Unhandled exception", exception);
        return toResponse(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    private static ResponseEntity<ErrorDto> toResponse(ErrorCode errorCode) {
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorDto.of(errorCode));
    }
}
