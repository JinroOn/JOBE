package com.jinroon.jobe.domain.auth.service;

import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.global.mail.MailService;
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
import com.jinroon.jobe.domain.auth.dto.request.PasswordResetConfirmRequest;
import com.jinroon.jobe.domain.auth.dto.request.PasswordResetIssueRequest;
import com.jinroon.jobe.domain.auth.dto.response.PasswordResetResponse;
import com.jinroon.jobe.domain.auth.entity.PasswordResetToken;
import com.jinroon.jobe.domain.auth.repository.PasswordResetTokenRepository;
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
    private static final int EMAIL_VERIFICATION_CODE_BOUND = 1_000_000;
    private static final int REFRESH_TOKEN_DAYS = 14;
    private static final int EMAIL_TOKEN_MINUTES = 30;
    private static final int PASSWORD_RESET_TOKEN_MINUTES = 30;

    private final UserRepository userRepository;
    private final UserConsentRepository userConsentRepository;
    private final SessionRepository sessionRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordHasher passwordHasher;
    private final JwtProvider jwtProvider;
    private final MailService mailService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public UserResponse signUp(SignUpRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new CustomException(ErrorCode.USER_EMAIL_DUPLICATE);
        }
        requireVerifiedEmail(request.email());
        User user = User.registerEmailUser(
                request.email(),
                passwordHasher.hash(request.password()),
                request.nickname(),
                request.profileImageUrl()
        );
        user.verifyEmail();
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
        if (userRepository.existsByEmail(request.email())) {
            throw new CustomException(ErrorCode.USER_EMAIL_DUPLICATE);
        }
        emailVerificationRepository.deleteByEmailAndUsedFalse(request.email());
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(EMAIL_TOKEN_MINUTES);
        EmailVerification verification = emailVerificationRepository.save(
                EmailVerification.issue(request.email(), issueEmailVerificationCode(), expiresAt)
        );
        mailService.sendEmailVerification(verification.getEmail(), verification.getToken());
        return EmailVerificationResponse.from(verification);
    }

    @Transactional
    public EmailVerificationResponse confirmEmailVerification(EmailVerificationConfirmRequest request) {
        EmailVerification verification = emailVerificationRepository.findByEmailAndToken(
                        request.email(),
                        request.token()
                )
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_EMAIL_VERIFICATION_NOT_FOUND));
        if (verification.getUsed()) {
            throw new CustomException(ErrorCode.AUTH_EMAIL_VERIFICATION_ALREADY_USED);
        }
        if (verification.isExpired(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.AUTH_EMAIL_VERIFICATION_EXPIRED);
        }
        if (userRepository.existsByEmail(verification.getEmail())) {
            throw new CustomException(ErrorCode.USER_EMAIL_DUPLICATE);
        }
        verification.markUsed();
        return EmailVerificationResponse.from(verification);
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

    @Transactional
    public PasswordResetResponse issuePasswordReset(PasswordResetIssueRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        requireActive(user);

        passwordResetTokenRepository.deleteByEmailAndUsedFalse(user.getEmail());
        PasswordResetToken resetToken = passwordResetTokenRepository.save(
                PasswordResetToken.issue(
                        user.getEmail(),
                        issueUniquePasswordResetCode(),
                        LocalDateTime.now().plusMinutes(PASSWORD_RESET_TOKEN_MINUTES)
                )
        );
        mailService.sendPasswordReset(resetToken.getEmail(), resetToken.getToken());
        return PasswordResetResponse.from(resetToken);
    }

    @Transactional
    public void confirmPasswordReset(PasswordResetConfirmRequest request) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByEmailAndToken(
                        request.email(),
                        request.code()
                )
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_PASSWORD_RESET_NOT_FOUND));
        if (resetToken.getUsed()) {
            throw new CustomException(ErrorCode.AUTH_PASSWORD_RESET_ALREADY_USED);
        }
        if (resetToken.isExpired(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.AUTH_PASSWORD_RESET_EXPIRED);
        }

        User user = userRepository.findByEmail(resetToken.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        requireActive(user);
        user.changePassword(passwordHasher.hash(request.newPassword()));
        resetToken.markUsed();
        sessionRepository.deleteByUserId(user.getId());
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

    private void requireVerifiedEmail(String email) {
        EmailVerification verification = emailVerificationRepository.findFirstByEmailAndUsedTrueOrderByUpdatedAtDesc(email)
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_EMAIL_VERIFICATION_REQUIRED));
        if (verification.isExpired(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.AUTH_EMAIL_VERIFICATION_EXPIRED);
        }
    }

    private String issueToken(int byteSize) {
        byte[] bytes = new byte[byteSize];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String issueEmailVerificationCode() {
        return issueSixDigitCode();
    }

    private String issueSixDigitCode() {
        return "%06d".formatted(secureRandom.nextInt(EMAIL_VERIFICATION_CODE_BOUND));
    }

    private String issueUniquePasswordResetCode() {
        String code;
        do {
            code = issueSixDigitCode();
        } while (passwordResetTokenRepository.existsByToken(code));
        return code;
    }
}
