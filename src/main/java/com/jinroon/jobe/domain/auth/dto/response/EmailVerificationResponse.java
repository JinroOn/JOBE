package com.jinroon.jobe.domain.auth.dto.response;

import java.time.LocalDateTime;

public record EmailVerificationResponse(
        String email,
        String token,
        LocalDateTime expiresAt
) {
}
