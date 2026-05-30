package com.jinroon.jobe.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "사용자 생성 및 수정 요청 데이터")
public class UserRequest {

    @Schema(description = "이메일", example = "test@example.com")
    private String email;

    @Schema(description = "비밀번호 해시", example = "$2a$10$...")
    private String passwordHash;

    @Schema(description = "닉네임", example = "jobe_user")
    private String nickname;

    @Schema(description = "프로필 이미지 URL", example = "https://example.com/image.png")
    private String profileImageUrl;

    @Schema(description = "역할", example = "member")
    private String role;

    @Schema(description = "로그인 타입", example = "email")
    private String loginType;

    @Schema(description = "상태", example = "active")
    private String status;
}
