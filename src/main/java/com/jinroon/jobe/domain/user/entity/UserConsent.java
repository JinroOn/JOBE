package com.jinroon.jobe.domain.user.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "user_consents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserConsent extends BaseEntitySupport {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "privacy_agreed", nullable = false)
    private Boolean privacyAgreed;

    @Column(name = "terms_agreed", nullable = false)
    private Boolean termsAgreed;

    @Column(name = "terms_version", nullable = false, length = 20)
    private String termsVersion;

    @Column(name = "marketing_agreed", nullable = false)
    private Boolean marketingAgreed;

    @Column(name = "agreed_at", nullable = false)
    private LocalDateTime agreedAt;

    public static UserConsent of(Long userId, Boolean termsAgreed, Boolean privacyAgreed, Boolean marketingAgreed) {
        UserConsent consent = new UserConsent();
        consent.userId = userId;
        consent.termsAgreed = termsAgreed;
        consent.privacyAgreed = privacyAgreed;
        consent.marketingAgreed = marketingAgreed;
        consent.termsVersion = "1.0";
        consent.agreedAt = LocalDateTime.now();
        return consent;
    }
}
