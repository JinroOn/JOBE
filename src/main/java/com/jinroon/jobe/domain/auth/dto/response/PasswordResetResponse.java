package com.jinroon.jobe.domain.auth.dto.response;

import com.jinroon.jobe.domain.auth.entity.PasswordResetToken;
import java.time.LocalDateTime;

public record PasswordResetResponse(
        String email,
        LocalDateTime expiresAt
) {
    public static PasswordResetResponse from(PasswordResetToken resetToken) {
        return new PasswordResetResponse(
                resetToken.getEmail(),
                resetToken.getExpiresAt()
        );
    }
}
