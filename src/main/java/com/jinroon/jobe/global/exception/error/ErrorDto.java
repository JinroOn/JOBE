package com.jinroon.jobe.global.exception.error;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 오류 응답 본문.
 * status·message는 기존 형식 그대로 두고, 프론트가 메시지 문구 대신 분기에 쓸 수 있도록
 * ErrorCode 이름을 code로 함께 내려준다(예: "AUTH_TOO_MANY_ATTEMPTS").
 */
@Getter
@AllArgsConstructor
public class ErrorDto {
    private final int status;
    private final String code;
    private final String message;

    public static ErrorDto of(ErrorCode errorCode) {
        return new ErrorDto(errorCode.getStatus(), errorCode.name(), errorCode.getMessage());
    }
}
