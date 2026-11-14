package com.jinroon.jobe.domain.auth.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "password_reset_tokens",
        indexes = {
                @Index(name = "idx_password_reset_email", columnList = "email"),
                @Index(name = "idx_password_reset_token", columnList = "token", unique = true)
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordResetToken extends BaseEntitySupport {

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 128, unique = true)
    private String token;

    @Column(name = "is_used", nullable = false)
    private Boolean used;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    // 기존 행과의 호환을 위해 nullable. null은 0회로 취급한다.
    @Column(name = "failed_attempts")
    private Integer failedAttempts;

    public static PasswordResetToken issue(String email, String token, LocalDateTime expiresAt) {
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.email = email;
        resetToken.token = token;
        resetToken.used = false;
        resetToken.expiresAt = expiresAt;
        resetToken.failedAttempts = 0;
        return resetToken;
    }

    public void markUsed() {
        used = true;
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt.isBefore(now);
    }

    public boolean matchesCode(String code) {
        if (code == null || token == null) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8));
    }

    public void recordFailedAttempt() {
        failedAttempts = getFailedAttemptCount() + 1;
    }

    public int getFailedAttemptCount() {
        return failedAttempts == null ? 0 : failedAttempts;
    }

    public boolean isAttemptLimitReached(int maxAttempts) {
        return getFailedAttemptCount() >= maxAttempts;
    }
}
