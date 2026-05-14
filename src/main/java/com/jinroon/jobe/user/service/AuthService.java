package com.jinroon.jobe.user.service;

import com.jinroon.jobe.common.error.UnauthorizedException;
import com.jinroon.jobe.user.domain.EmailVerification;
import com.jinroon.jobe.user.domain.Session;
import com.jinroon.jobe.user.domain.User;
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
import com.jinroon.jobe.user.repository.EmailVerificationRepository;
import com.jinroon.jobe.user.repository.SessionRepository;
import com.jinroon.jobe.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int REFRESH_TOKEN_BYTES = 48;
    private static final int EMAIL_TOKEN_BYTES = 32;
    private static final int REFRESH_TOKEN_DAYS = 14;
    private static final int EMAIL_TOKEN_MINUTES = 30;

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final PasswordHasher passwordHasher;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public UserResponse signUp(SignUpRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Email already exists");
        }
        User user = User.registerEmailUser(
                request.email(),
                passwordHasher.hash(request.password()),
                request.nickname(),
                request.profileImageUrl()
        );
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));
        requireActive(user);
        if (!passwordHasher.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }
        user.recordLogin();
        Session session = issueSession(user, request.deviceInfo());
        return new AuthResponse(UserResponse.from(user), session.getRefreshToken(), session.getExpiresAt());
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        Session currentSession = sessionRepository.findByRefreshToken(request.refreshToken())
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        if (currentSession.isExpired(LocalDateTime.now())) {
            sessionRepository.delete(currentSession);
            throw new UnauthorizedException("Expired refresh token");
        }
        User user = userRepository.findById(currentSession.getUserId())
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        requireActive(user);
        sessionRepository.delete(currentSession);
        Session newSession = issueSession(user, request.deviceInfo());
        return new AuthResponse(UserResponse.from(user), newSession.getRefreshToken(), newSession.getExpiresAt());
    }

    @Transactional
    public void logout(LogoutRequest request) {
        sessionRepository.deleteByRefreshToken(request.refreshToken());
    }

    @Transactional
    public EmailVerificationResponse issueEmailVerification(EmailVerificationIssueRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new IllegalArgumentException("User not found by email"));
        requireActive(user);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(EMAIL_TOKEN_MINUTES);
        EmailVerification verification = emailVerificationRepository.save(
                EmailVerification.issue(user.getEmail(), issueToken(EMAIL_TOKEN_BYTES), expiresAt)
        );
        return new EmailVerificationResponse(verification.getEmail(), verification.getToken(), verification.getExpiresAt());
    }

    @Transactional
    public UserResponse confirmEmailVerification(EmailVerificationConfirmRequest request) {
        EmailVerification verification = emailVerificationRepository.findByToken(request.token())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email verification token"));
        if (verification.getUsed()) {
            throw new IllegalArgumentException("Email verification token already used");
        }
        if (verification.isExpired(LocalDateTime.now())) {
            throw new IllegalArgumentException("Email verification token expired");
        }
        User user = userRepository.findByEmail(verification.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("User not found by email"));
        requireActive(user);
        verification.markUsed();
        user.verifyEmail();
        return UserResponse.from(user);
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        Session session = sessionRepository.findByRefreshToken(request.refreshToken())
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        if (session.isExpired(LocalDateTime.now())) {
            sessionRepository.delete(session);
            throw new UnauthorizedException("Expired refresh token");
        }
        User user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        requireActive(user);
        if (!passwordHasher.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid current password");
        }
        user.changePassword(passwordHasher.hash(request.newPassword()));
        sessionRepository.delete(session);
    }

    private Session issueSession(User user, String deviceInfo) {
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(REFRESH_TOKEN_DAYS);
        return sessionRepository.save(Session.issue(user.getId(), issueToken(REFRESH_TOKEN_BYTES), deviceInfo, expiresAt));
    }

    private void requireActive(User user) {
        if (!user.isActive()) {
            throw new UnauthorizedException("Inactive user");
        }
    }

    private String issueToken(int byteSize) {
        byte[] bytes = new byte[byteSize];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
