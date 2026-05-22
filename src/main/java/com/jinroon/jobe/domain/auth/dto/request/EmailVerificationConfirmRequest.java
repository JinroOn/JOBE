package com.jinroon.jobe.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailVerificationConfirmRequest(
        @NotBlank @Size(max = 128) String token
) {
}
