package com.jinroon.jobe.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "이메일 인증 생성 요청 데이터")
public class EmailVerificationRequest {

    @Schema(description = "이메일", example = "test@example.com")
    @Email
    private String email;

    @Schema(description = "인증 토큰", example = "token")
    @Size(max = 128)
    private String token;

    @Schema(description = "사용 여부", example = "false")
    private Boolean used;

    @Schema(description = "만료 일시", example = "2026-05-31T12:30:00")
    private LocalDateTime expiresAt;
}
