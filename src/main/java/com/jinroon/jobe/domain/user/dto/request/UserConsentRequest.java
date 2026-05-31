package com.jinroon.jobe.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "약관 동의 요청 데이터")
public class UserConsentRequest {

    @Schema(description = "개인정보 동의 여부", example = "true")
    private Boolean privacyAgreed;

    @Schema(description = "약관 동의 여부", example = "true")
    private Boolean termsAgreed;

    @Schema(description = "약관 버전", example = "1.0")
    @Size(max = 20)
    private String termsVersion;

    @Schema(description = "마케팅 동의 여부", example = "false")
    private Boolean marketingAgreed;

    @Schema(description = "동의 일시", example = "2026-05-31T12:00:00")
    private LocalDateTime agreedAt;
}
