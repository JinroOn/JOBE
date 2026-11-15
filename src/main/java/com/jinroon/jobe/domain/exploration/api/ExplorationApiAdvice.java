package com.jinroon.jobe.domain.exploration.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ExplorationSessionController.class, ExplorationResultController.class, DiagnosisHistoryController.class})
public class ExplorationApiAdvice {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Failure(int status, String code, String message, Long existingSessionId) {}

    @ExceptionHandler(ExplorationApiException.class)
    ResponseEntity<Failure> exploration(ExplorationApiException e) {
        return ResponseEntity.status(e.getStatus()).body(
                new Failure(e.getStatus(), e.getCode(), e.getMessage(), e.getExistingSessionId()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Failure> invalid(Exception e) {
        return ResponseEntity.badRequest().body(new Failure(400, "INVALID_REQUEST", "요청 형식과 필수 값을 확인해 주세요.", null));
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<Failure> concurrent(Exception e) {
        return ResponseEntity.status(503).body(new Failure(503, "CONCURRENT_OPERATION_RETRYABLE",
                "동시 요청 처리 중입니다. 같은 멱등 키와 본문으로 다시 요청해 주세요.", null));
    }
}
