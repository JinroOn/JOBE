package com.jinroon.jobe.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "약관 동의 요청 데이터")
public class UserConsentRequest {

    @Schema(description = "동의 항목 종류", example = "TERMS")
    private String consentType;

    @Schema(description = "동의 여부", example = "true")
    private Boolean agreed;
}
