package com.jinroon.jobe.domain.auth.service;

import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.global.security.JwtProvider;
import com.jinroon.jobe.domain.user.entity.EmailVerification;
import com.jinroon.jobe.domain.user.entity.Session;
import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.auth.dto.response.AuthResponse;
import com.jinroon.jobe.domain.auth.dto.request.ChangePasswordRequest;
import com.jinroon.jobe.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.jinroon.jobe.domain.auth.dto.request.EmailVerificationIssueRequest;
import com.jinroon.jobe.domain.auth.dto.response.EmailVerificationResponse;
import com.jinroon.jobe.domain.auth.dto.request.LoginRequest;
import com.jinroon.jobe.domain.auth.dto.request.LogoutRequest;
import com.jinroon.jobe.domain.auth.dto.request.RefreshTokenRequest;
import com.jinroon.jobe.domain.auth.dto.request.SignUpRequest;
import com.jinroon.jobe.domain.user.dto.response.UserResponse;
import com.jinroon.jobe.domain.user.entity.UserConsent;
import com.jinroon.jobe.domain.user.repository.EmailVerificationRepository;
import com.jinroon.jobe.domain.user.repository.SessionRepository;
import com.jinroon.jobe.domain.user.repository.UserConsentRepository;
import com.jinroon.jobe.domain.user.repository.UserRepository;
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
    private final UserConsentRepository userConsentRepository;
    private final SessionRepository sessionRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final PasswordHasher passwordHasher;
    private final JwtProvider jwtProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public UserResponse signUp(SignUpRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new CustomException(ErrorCode.USER_EMAIL_DUPLICATE);
        }
        User user = User.registerEmailUser(
                request.email(),
                passwordHasher.hash(request.password()),
                request.nickname(),
                request.profileImageUrl()
        );
        userRepository.save(user);
        userConsentRepository.save(UserConsent.of(user.getId(), request.termsAgreed(), request.privacyAgreed(), request.marketingAgreed()));
        return UserResponse.from(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_PASSWORD_INVALID));
        requireActive(user);
        if (!passwordHasher.matches(request.password(), user.getPasswordHash())) {
            throw new CustomException(ErrorCode.USER_PASSWORD_INVALID);
        }
        user.recordLogin();
        Session session = issueSession(user, request.deviceInfo());
        String accessToken = jwtProvider.createToken(user);
        return new AuthResponse(UserResponse.from(user), accessToken, session.getRefreshToken(), session.getExpiresAt());
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        Session currentSession = sessionRepository.findByRefreshToken(request.refreshToken())
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_TOKEN_INVALID));
        if (currentSession.isExpired(LocalDateTime.now())) {
            sessionRepository.delete(currentSession);
            throw new CustomException(ErrorCode.AUTH_TOKEN_EXPIRED);
        }
        User user = userRepository.findById(currentSession.getUserId())
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_TOKEN_INVALID));
        requireActive(user);
        sessionRepository.delete(currentSession);
        Session newSession = issueSession(user, request.deviceInfo());
        String accessToken = jwtProvider.createToken(user);
        return new AuthResponse(UserResponse.from(user), accessToken, newSession.getRefreshToken(), newSession.getExpiresAt());
    }

    @Transactional
    public void logout(LogoutRequest request) {
        sessionRepository.deleteByRefreshToken(request.refreshToken());
    }

    @Transactional
    public EmailVerificationResponse issueEmailVerification(EmailVerificationIssueRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
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
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_EMAIL_VERIFICATION_NOT_FOUND));
        if (verification.getUsed()) {
            throw new CustomException(ErrorCode.AUTH_EMAIL_VERIFICATION_ALREADY_USED);
        }
        if (verification.isExpired(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.AUTH_EMAIL_VERIFICATION_EXPIRED);
        }
        User user = userRepository.findByEmail(verification.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        requireActive(user);
        verification.markUsed();
        user.verifyEmail();
        return UserResponse.from(user);
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        Session session = sessionRepository.findByRefreshToken(request.refreshToken())
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_TOKEN_INVALID));
        if (session.isExpired(LocalDateTime.now())) {
            sessionRepository.delete(session);
            throw new CustomException(ErrorCode.AUTH_TOKEN_EXPIRED);
        }
        User user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_TOKEN_INVALID));
        requireActive(user);
        if (!passwordHasher.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new CustomException(ErrorCode.USER_PASSWORD_INVALID);
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
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
    }

    private String issueToken(int byteSize) {
        byte[] bytes = new byte[byteSize];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
