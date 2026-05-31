package com.jinroon.jobe.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "세션 생성 요청 데이터")
public class SessionRequest {

    @Schema(description = "사용자 ID", example = "1")
    private Long userId;

    @Schema(description = "리프레시 토큰", example = "abc123xyz")
    @Size(max = 512)
    private String refreshToken;

    @Schema(description = "기기 정보", example = "iPhone 13")
    @Size(max = 255)
    private String deviceInfo;

    @Schema(description = "만료 일시", example = "2026-06-14T12:00:00")
    private LocalDateTime expiresAt;
}
