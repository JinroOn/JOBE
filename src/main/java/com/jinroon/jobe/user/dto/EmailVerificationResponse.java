package com.jinroon.jobe.user.dto;

import java.time.LocalDateTime;

public record EmailVerificationResponse(
        String email,
        String token,
        LocalDateTime expiresAt
) {
}
