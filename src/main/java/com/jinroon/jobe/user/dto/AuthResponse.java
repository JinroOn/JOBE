package com.jinroon.jobe.user.dto;

import java.time.LocalDateTime;

public record AuthResponse(
        UserResponse user,
        String refreshToken,
        LocalDateTime expiresAt
) {
}
