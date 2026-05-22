package com.jinroon.jobe.domain.auth.dto.response;

import com.jinroon.jobe.domain.user.dto.response.UserResponse;
import java.time.LocalDateTime;

public record AuthResponse(
        UserResponse user,
        String refreshToken,
        LocalDateTime expiresAt
) {
}
