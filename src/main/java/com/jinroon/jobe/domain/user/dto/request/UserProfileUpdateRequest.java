package com.jinroon.jobe.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 본인 프로필 수정 요청(PATCH /api/users/me). 닉네임·프로필 이미지만 받는다.
 * role·status·email 등은 이 요청으로 바꿀 수 없다.
 */
@Schema(description = "본인 프로필 수정 요청")
public record UserProfileUpdateRequest(
        @Schema(description = "닉네임", example = "진로탐험가")
        @Size(max = 50)
        String nickname,

        @Schema(description = "프로필 이미지 URL", example = "https://example.com/profile.png")
        @Size(max = 500)
        String profileImageUrl
) {
}
