package com.jinroon.jobe.domain.auth.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
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

    public static PasswordResetToken issue(String email, String token, LocalDateTime expiresAt) {
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.email = email;
        resetToken.token = token;
        resetToken.used = false;
        resetToken.expiresAt = expiresAt;
        return resetToken;
    }

    public void markUsed() {
        used = true;
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt.isBefore(now);
    }
}
