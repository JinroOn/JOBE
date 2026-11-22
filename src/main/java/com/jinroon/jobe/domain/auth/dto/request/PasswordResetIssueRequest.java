package com.jinroon.jobe.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetIssueRequest(
        @Email @NotBlank String email
) {
}
