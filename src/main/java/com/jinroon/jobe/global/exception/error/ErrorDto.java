package com.jinroon.jobe.global.exception.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 오류 응답 본문.
 * status·message는 기존 형식 그대로 두고, 프론트가 메시지 문구 대신 분기에 쓸 수 있도록
 * ErrorCode 이름을 code로 함께 내려준다(예: "AUTH_TOO_MANY_ATTEMPTS").
 * 입력값 검증 오류만 필드별 메시지를 fieldErrors로 추가한다. 다른 오류에는 이 키가 없다.
 */
@Getter
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorDto {
    private final int status;
    private final String code;
    private final String message;
    private final Map<String, String> fieldErrors;

    public static ErrorDto of(ErrorCode errorCode) {
        return new ErrorDto(errorCode.getStatus(), errorCode.name(), errorCode.getMessage(), null);
    }

    public static ErrorDto withFieldErrors(ErrorCode errorCode, Map<String, String> fieldErrors) {
        return new ErrorDto(errorCode.getStatus(), errorCode.name(), errorCode.getMessage(), fieldErrors);
    }
}
