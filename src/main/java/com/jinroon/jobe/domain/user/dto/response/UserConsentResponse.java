package com.jinroon.jobe.domain.user.dto.response;

import com.jinroon.jobe.domain.user.entity.UserConsent;
import java.time.LocalDateTime;

public record UserConsentResponse(
        Long id,
        Long userId,
        Boolean privacyAgreed,
        Boolean termsAgreed,
        String termsVersion,
        LocalDateTime agreedAt
) {
    public static UserConsentResponse from(UserConsent consent) {
        return new UserConsentResponse(
                consent.getId(),
                consent.getUserId(),
                consent.getPrivacyAgreed(),
                consent.getTermsAgreed(),
                consent.getTermsVersion(),
                consent.getAgreedAt()
        );
    }
}
