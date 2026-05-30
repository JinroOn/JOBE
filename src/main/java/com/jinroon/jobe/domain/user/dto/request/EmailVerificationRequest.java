package com.jinroon.jobe.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "이메일 인증 생성 요청 데이터")
public class EmailVerificationRequest {

    @Schema(description = "사용자 ID", example = "1")
    private Long userId;

    @Schema(description = "인증 코드", example = "123456")
    private String code;
}
