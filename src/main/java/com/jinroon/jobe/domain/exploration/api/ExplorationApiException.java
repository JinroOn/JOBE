package com.jinroon.jobe.domain.exploration.api;

import lombok.Getter;

@Getter
public class ExplorationApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Long existingSessionId;

    public ExplorationApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ExplorationApiException(int status, String code, String message, Long existingSessionId) {
        super(message);
        this.status = status;
        this.code = code;
        this.existingSessionId = existingSessionId;
    }

    public static ExplorationApiException integrity() {
        return new ExplorationApiException(503, "CATALOG_INTEGRITY_ERROR", "고정된 탐색 자료를 검증할 수 없습니다.");
    }
}
