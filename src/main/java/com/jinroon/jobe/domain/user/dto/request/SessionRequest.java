package com.jinroon.jobe.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "세션 생성 요청 데이터")
public class SessionRequest {

    @Schema(description = "사용자 ID", example = "1")
    private Long userId;

    @Schema(description = "리프레시 토큰", example = "abc123xyz")
    private String refreshToken;

    @Schema(description = "기기 정보", example = "iPhone 13")
    private String deviceInfo;

    @Schema(description = "IP 주소", example = "192.168.0.1")
    private String ipAddress;
}
