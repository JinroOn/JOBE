package com.jinroon.jobe.domain.user.entity;

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
@Table(name = "email_verifications", indexes = @Index(name = "idx_email_verif_email", columnList = "email"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerification extends BaseEntitySupport {

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 128)
    private String token;

    @Column(name = "is_used", nullable = false)
    private Boolean used;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static EmailVerification issue(String email, String token, LocalDateTime expiresAt) {
        EmailVerification verification = new EmailVerification();
        verification.email = email;
        verification.token = token;
        verification.used = false;
        verification.expiresAt = expiresAt;
        verification.createdAt = LocalDateTime.now();
        return verification;
    }

    public void markUsed() {
        this.used = true;
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt.isBefore(now);
    }
}
