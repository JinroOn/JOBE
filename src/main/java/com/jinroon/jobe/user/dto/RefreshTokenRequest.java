package com.jinroon.jobe.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshTokenRequest(
        @NotBlank @Size(max = 512) String refreshToken,
        @Size(max = 255) String deviceInfo
) {
}
