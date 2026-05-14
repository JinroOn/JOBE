package com.jinroon.jobe.user.controller;

import com.jinroon.jobe.user.dto.AuthResponse;
import com.jinroon.jobe.user.dto.ChangePasswordRequest;
import com.jinroon.jobe.user.dto.EmailVerificationConfirmRequest;
import com.jinroon.jobe.user.dto.EmailVerificationIssueRequest;
import com.jinroon.jobe.user.dto.EmailVerificationResponse;
import com.jinroon.jobe.user.dto.LoginRequest;
import com.jinroon.jobe.user.dto.LogoutRequest;
import com.jinroon.jobe.user.dto.RefreshTokenRequest;
import com.jinroon.jobe.user.dto.SignUpRequest;
import com.jinroon.jobe.user.dto.UserResponse;
import com.jinroon.jobe.user.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse signUp(@Valid @RequestBody SignUpRequest request) {
        return authService.signUp(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request);
    }

    @PostMapping("/email-verifications")
    @ResponseStatus(HttpStatus.CREATED)
    public EmailVerificationResponse issueEmailVerification(@Valid @RequestBody EmailVerificationIssueRequest request) {
        return authService.issueEmailVerification(request);
    }

    @PostMapping("/email-verifications/confirm")
    public UserResponse confirmEmailVerification(@Valid @RequestBody EmailVerificationConfirmRequest request) {
        return authService.confirmEmailVerification(request);
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
    }
}
