package com.jinroon.jobe.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignUpRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotBlank @Size(max = 50) String nickname,
        @Size(max = 500) String profileImageUrl,
        boolean termsAgreed,
        boolean privacyAgreed,
        boolean marketingAgreed
) {
}
