package com.jinroon.jobe.domain.auth.dto.response;

import com.jinroon.jobe.domain.user.entity.EmailVerification;
import java.time.LocalDateTime;

public record EmailVerificationResponse(
        String email,
        String token,
        LocalDateTime expiresAt
) {
    public static EmailVerificationResponse from(EmailVerification verification) {
        return new EmailVerificationResponse(
                verification.getEmail(),
                verification.getToken(),
                verification.getExpiresAt()
        );
    }
}
