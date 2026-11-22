package com.jinroon.jobe.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jinroon.jobe.domain.auth.dto.request.ChangePasswordRequest;
import com.jinroon.jobe.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.jinroon.jobe.domain.auth.dto.request.EmailVerificationIssueRequest;
import com.jinroon.jobe.domain.auth.dto.request.LoginRequest;
import com.jinroon.jobe.domain.auth.dto.request.LogoutRequest;
import com.jinroon.jobe.domain.auth.dto.request.PasswordResetConfirmRequest;
import com.jinroon.jobe.domain.auth.dto.request.PasswordResetIssueRequest;
import com.jinroon.jobe.domain.auth.dto.request.RefreshTokenRequest;
import com.jinroon.jobe.domain.auth.dto.request.SignUpRequest;
import com.jinroon.jobe.domain.auth.dto.response.AuthResponse;
import com.jinroon.jobe.domain.auth.entity.PasswordResetToken;
import com.jinroon.jobe.domain.auth.repository.PasswordResetTokenRepository;
import com.jinroon.jobe.domain.user.dto.response.UserResponse;
import com.jinroon.jobe.domain.user.entity.EmailVerification;
import com.jinroon.jobe.domain.user.entity.Session;
import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.entity.UserConsent;
import com.jinroon.jobe.domain.user.enums.UserEnums.UserStatus;
import com.jinroon.jobe.domain.user.repository.EmailVerificationRepository;
import com.jinroon.jobe.domain.user.repository.SessionRepository;
import com.jinroon.jobe.domain.user.repository.UserConsentRepository;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.global.mail.MailService;
import com.jinroon.jobe.global.security.JwtProvider;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 인증 서비스 단위 테스트.
 * 1단계 기준선 + 2단계(코드 시도 횟수 제한, 발급 제한, 재설정 응답 통일)
 * + 3단계(비밀번호 변경 시 전체 세션 삭제, refresh 토큰 해시 저장).
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String EMAIL = "student@example.com";
    private static final String PASSWORD = "password123";
    private static final String JWT_SECRET = "test-secret-key-must-be-at-least-256-bits-long-for-hs256";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserConsentRepository userConsentRepository;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private EmailVerificationRepository emailVerificationRepository;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private MailService mailService;

    private final PasswordHasher passwordHasher = new PasswordHasher();
    private final JwtProvider jwtProvider = new JwtProvider(JWT_SECRET);
    private final MutableClock clock = new MutableClock(java.time.Instant.parse("2026-11-22T00:00:00Z"));
    private final LoginAttemptLimiter loginAttemptLimiter = new LoginAttemptLimiter(clock);

    /** 로그인 제한 시간 경과를 확인하기 위한 테스트용 시계. */
    private static final class MutableClock extends java.time.Clock {
        private java.time.Instant now;

        MutableClock(java.time.Instant now) { this.now = now; }

        void advance(java.time.Duration duration) { now = now.plus(duration); }

        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }

        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }

        @Override public java.time.Instant instant() { return now; }
    }

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(
                userRepository,
                userConsentRepository,
                sessionRepository,
                emailVerificationRepository,
                passwordResetTokenRepository,
                passwordHasher,
                jwtProvider,
                mailService,
                org.mockito.Mockito.mock(com.jinroon.jobe.domain.user.service.UserWriteGuard.class),
                loginAttemptLimiter
        );
    }

    // ---- 가입 ----

    @Test
    void signUpRejectsDuplicateEmail() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertErrorCode(() -> service.signUp(signUpRequest()), ErrorCode.USER_EMAIL_DUPLICATE);
        verify(userRepository, never()).save(any());
    }

    @Test
    void signUpRequiresVerifiedEmail() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(emailVerificationRepository.findFirstByEmailAndUsedTrueOrderByUpdatedAtDesc(EMAIL))
                .thenReturn(Optional.empty());

        assertErrorCode(() -> service.signUp(signUpRequest()), ErrorCode.AUTH_EMAIL_VERIFICATION_REQUIRED);
        verify(userRepository, never()).save(any());
    }

    @Test
    void signUpRejectsExpiredVerification() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(emailVerificationRepository.findFirstByEmailAndUsedTrueOrderByUpdatedAtDesc(EMAIL))
                .thenReturn(Optional.of(verification("123456", true, LocalDateTime.now().minusMinutes(1))));

        assertErrorCode(() -> service.signUp(signUpRequest()), ErrorCode.AUTH_EMAIL_VERIFICATION_EXPIRED);
    }

    @Test
    void signUpConvertsConcurrentUniqueViolationToDuplicate() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(emailVerificationRepository.findFirstByEmailAndUsedTrueOrderByUpdatedAtDesc(EMAIL))
                .thenReturn(Optional.of(verification("123456", true, LocalDateTime.now().plusMinutes(10))));
        when(userRepository.save(any(User.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_users_email"));

        assertErrorCode(() -> service.signUp(signUpRequest()), ErrorCode.USER_EMAIL_DUPLICATE);
        verify(userConsentRepository, never()).save(any());
    }

    @Test
    void signUpCreatesActiveMemberWithHashedPasswordAndConsent() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(emailVerificationRepository.findFirstByEmailAndUsedTrueOrderByUpdatedAtDesc(EMAIL))
                .thenReturn(Optional.of(verification("123456", true, LocalDateTime.now().plusMinutes(10))));

        UserResponse response = service.signUp(signUpRequest());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getEmail()).isEqualTo(EMAIL);
        assertThat(saved.getRoleName()).isEqualTo("member");
        assertThat(saved.getStatusName()).isEqualTo("active");
        assertThat(saved.getLoginTypeName()).isEqualTo("email");
        assertThat(saved.getEmailVerifiedAt()).isNotNull();
        assertThat(saved.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwordHasher.matches(PASSWORD, saved.getPasswordHash())).isTrue();
        assertThat(response.role()).isEqualTo("member");

        ArgumentCaptor<UserConsent> consentCaptor = ArgumentCaptor.forClass(UserConsent.class);
        verify(userConsentRepository).save(consentCaptor.capture());
        assertThat(consentCaptor.getValue().getTermsAgreed()).isTrue();
        assertThat(consentCaptor.getValue().getPrivacyAgreed()).isTrue();
        assertThat(consentCaptor.getValue().getMarketingAgreed()).isFalse();
    }

    // ---- 로그인 ----

    @Test
    void loginRejectsUnknownEmailWithPasswordError() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.login(loginRequest(PASSWORD)), ErrorCode.USER_PASSWORD_INVALID);
    }

    @Test
    void loginRejectsWrongPassword() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(activeUser(1L)));

        assertErrorCode(() -> service.login(loginRequest("wrong-password")), ErrorCode.USER_PASSWORD_INVALID);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void repeatedWrongPasswordsBlockLoginEvenWithCorrectPasswordUntilWindowPasses() {
        User user = activeUser(1L);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(sessionRepository.save(any(Session.class))).thenAnswer(invocation -> invocation.getArgument(0));

        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
            assertErrorCode(() -> service.login(loginRequest("wrong-password")), ErrorCode.USER_PASSWORD_INVALID);
        }
        // 한도에 도달하면 맞는 비밀번호여도 세션을 만들지 않는다.
        assertErrorCode(() -> service.login(loginRequest(PASSWORD)), ErrorCode.AUTH_TOO_MANY_LOGIN_ATTEMPTS);
        verify(sessionRepository, never()).save(any());

        clock.advance(LoginAttemptLimiter.WINDOW);
        assertThat(service.login(loginRequest(PASSWORD)).accessToken()).isNotBlank();
    }

    @Test
    void unknownEmailIsLimitedLikeExistingEmail() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
            assertErrorCode(() -> service.login(loginRequest(PASSWORD)), ErrorCode.USER_PASSWORD_INVALID);
        }
        // 대소문자나 앞뒤 공백을 바꿔도 같은 이메일로 센다.
        assertErrorCode(() -> service.login(new LoginRequest(" Student@Example.com ", PASSWORD, "test-device")),
                ErrorCode.AUTH_TOO_MANY_LOGIN_ATTEMPTS);
    }

    @Test
    void successfulLoginResetsFailureCount() {
        User user = activeUser(1L);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(sessionRepository.save(any(Session.class))).thenAnswer(invocation -> invocation.getArgument(0));

        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES - 1; i++) {
            assertErrorCode(() -> service.login(loginRequest("wrong-password")), ErrorCode.USER_PASSWORD_INVALID);
        }
        service.login(loginRequest(PASSWORD));
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES - 1; i++) {
            assertErrorCode(() -> service.login(loginRequest("wrong-password")), ErrorCode.USER_PASSWORD_INVALID);
        }
        assertThat(service.login(loginRequest(PASSWORD)).accessToken()).isNotBlank();
    }

    @Test
    void loginRejectsInactiveUser() {
        User user = activeUser(1L);
        ReflectionTestUtils.setField(user, "status", UserStatus.suspended);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertErrorCode(() -> service.login(loginRequest(PASSWORD)), ErrorCode.UNAUTHORIZED);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void loginIssuesAccessTokenAndRefreshSession() {
        User user = activeUser(1L);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(sessionRepository.save(any(Session.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthResponse response = service.login(loginRequest(PASSWORD));

        assertThat(jwtProvider.isValid(response.accessToken())).isTrue();
        assertThat(jwtProvider.getClaims(response.accessToken()).getSubject()).isEqualTo(EMAIL);
        assertThat(jwtProvider.getClaims(response.accessToken()).get("userId", Long.class)).isEqualTo(1L);
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.expiresAt()).isAfter(LocalDateTime.now().plusDays(13));
        assertThat(user.getLastLoginAt()).isNotNull();

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getUserId()).isEqualTo(1L);
        assertThat(sessionCaptor.getValue().getDeviceInfo()).isEqualTo("test-device");
        // DB에는 원문이 아니라 해시만 저장된다.
        assertThat(sessionCaptor.getValue().getRefreshToken())
                .isNotEqualTo(response.refreshToken())
                .isEqualTo(AuthService.hashRefreshToken(response.refreshToken()))
                .hasSize(64);
    }

    // ---- refresh / logout ----

    @Test
    void refreshRejectsUnknownToken() {
        when(sessionRepository.findByRefreshToken(AuthService.hashRefreshToken("unknown"))).thenReturn(Optional.empty());

        assertErrorCode(() -> service.refresh(new RefreshTokenRequest("unknown", null)), ErrorCode.AUTH_TOKEN_INVALID);
    }

    @Test
    void refreshDeletesExpiredSession() {
        Session expired = session(1L, "old-token", LocalDateTime.now().minusMinutes(1));
        when(sessionRepository.findByRefreshToken(AuthService.hashRefreshToken("old-token"))).thenReturn(Optional.of(expired));

        assertErrorCode(() -> service.refresh(new RefreshTokenRequest("old-token", null)), ErrorCode.AUTH_TOKEN_EXPIRED);
        verify(sessionRepository).delete(expired);
    }

    @Test
    void refreshRejectsInactiveUser() {
        Session current = session(1L, "old-token", LocalDateTime.now().plusDays(1));
        User user = activeUser(1L);
        ReflectionTestUtils.setField(user, "status", UserStatus.withdrawn);
        when(sessionRepository.findByRefreshToken(AuthService.hashRefreshToken("old-token"))).thenReturn(Optional.of(current));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertErrorCode(() -> service.refresh(new RefreshTokenRequest("old-token", null)), ErrorCode.UNAUTHORIZED);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void refreshRotatesSession() {
        Session current = session(1L, "old-token", LocalDateTime.now().plusDays(1));
        when(sessionRepository.findByRefreshToken(AuthService.hashRefreshToken("old-token"))).thenReturn(Optional.of(current));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1L)));
        when(sessionRepository.deleteSessionById(current.getId())).thenReturn(1);
        when(sessionRepository.save(any(Session.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthResponse response = service.refresh(new RefreshTokenRequest("old-token", "new-device"));

        verify(sessionRepository).deleteSessionById(current.getId());
        assertThat(response.refreshToken()).isNotEqualTo("old-token");
        assertThat(jwtProvider.isValid(response.accessToken())).isTrue();
        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getRefreshToken())
                .isEqualTo(AuthService.hashRefreshToken(response.refreshToken()));
    }

    @Test
    void refreshRejectsTokenAlreadyRotatedByConcurrentRequest() {
        // 동시에 들어온 다른 요청이 먼저 세션을 지웠으면 새 토큰을 발급하지 않는다.
        Session current = session(1L, "old-token", LocalDateTime.now().plusDays(1));
        when(sessionRepository.findByRefreshToken(AuthService.hashRefreshToken("old-token"))).thenReturn(Optional.of(current));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1L)));
        when(sessionRepository.deleteSessionById(current.getId())).thenReturn(0);

        assertErrorCode(() -> service.refresh(new RefreshTokenRequest("old-token", null)), ErrorCode.AUTH_TOKEN_INVALID);
        verify(sessionRepository, never()).save(any(Session.class));
    }

    @Test
    void refreshDoesNotAcceptStoredHashAsToken() {
        // DB가 유출돼 해시를 알아도 그 값으로는 refresh할 수 없다.
        String storedHash = AuthService.hashRefreshToken("old-token");
        when(sessionRepository.findByRefreshToken(AuthService.hashRefreshToken(storedHash))).thenReturn(Optional.empty());

        assertErrorCode(() -> service.refresh(new RefreshTokenRequest(storedHash, null)), ErrorCode.AUTH_TOKEN_INVALID);
    }

    @Test
    void logoutDeletesSessionByRefreshToken() {
        service.logout(new LogoutRequest("some-token"));

        verify(sessionRepository).deleteByRefreshToken(AuthService.hashRefreshToken("some-token"));
    }

    // ---- 이메일 인증 ----

    @Test
    void issueEmailVerificationRejectsRegisteredEmail() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertErrorCode(
                () -> service.issueEmailVerification(new EmailVerificationIssueRequest(EMAIL)),
                ErrorCode.USER_EMAIL_DUPLICATE
        );
        verify(mailService, never()).sendEmailVerification(anyString(), anyString());
    }

    @Test
    void issueEmailVerificationSendsSixDigitCodeWithoutDeletingPreviousCodes() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(emailVerificationRepository.save(any(EmailVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.issueEmailVerification(new EmailVerificationIssueRequest(EMAIL));

        verify(emailVerificationRepository, never()).deleteByEmailAndUsedFalse(anyString());
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendEmailVerification(eq(EMAIL), codeCaptor.capture());
        assertThat(codeCaptor.getValue()).matches("\\d{6}");
        assertThat(response.email()).isEqualTo(EMAIL);
        assertThat(response.expiresAt()).isAfter(LocalDateTime.now().plusMinutes(29));
    }

    @Test
    void issueEmailVerificationRejectsResendWithinCooldown() {
        EmailVerification recent = verification("123456", false, LocalDateTime.now().plusMinutes(30));
        ReflectionTestUtils.setField(recent, "createdAt", LocalDateTime.now().minusSeconds(10));
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(emailVerificationRepository.findFirstByEmailOrderByIdDesc(EMAIL)).thenReturn(Optional.of(recent));

        assertErrorCode(
                () -> service.issueEmailVerification(new EmailVerificationIssueRequest(EMAIL)),
                ErrorCode.AUTH_TOO_MANY_REQUESTS
        );
        verify(mailService, never()).sendEmailVerification(anyString(), anyString());
    }

    @Test
    void issueEmailVerificationRejectsTooManyIssuesInWindow() {
        EmailVerification old = verification("123456", false, LocalDateTime.now().plusMinutes(30));
        ReflectionTestUtils.setField(old, "createdAt", LocalDateTime.now().minusMinutes(5));
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(emailVerificationRepository.findFirstByEmailOrderByIdDesc(EMAIL)).thenReturn(Optional.of(old));
        when(emailVerificationRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any()))
                .thenReturn((long) AuthService.MAX_ISSUES_PER_WINDOW);

        assertErrorCode(
                () -> service.issueEmailVerification(new EmailVerificationIssueRequest(EMAIL)),
                ErrorCode.AUTH_TOO_MANY_REQUESTS
        );
        verify(emailVerificationRepository, never()).save(any());
    }

    @Test
    void confirmEmailVerificationRejectsWhenNoCodeIssued() {
        when(emailVerificationRepository.findFirstByEmailOrderByIdDesc(EMAIL)).thenReturn(Optional.empty());

        assertErrorCode(
                () -> service.confirmEmailVerification(new EmailVerificationConfirmRequest(EMAIL, "000000")),
                ErrorCode.AUTH_EMAIL_VERIFICATION_NOT_FOUND
        );
    }

    @Test
    void confirmEmailVerificationCountsWrongCode() {
        EmailVerification verification = verification("123456", false, LocalDateTime.now().plusMinutes(10));
        when(emailVerificationRepository.findFirstByEmailOrderByIdDesc(EMAIL)).thenReturn(Optional.of(verification));

        assertErrorCode(
                () -> service.confirmEmailVerification(new EmailVerificationConfirmRequest(EMAIL, "000000")),
                ErrorCode.AUTH_EMAIL_VERIFICATION_NOT_FOUND
        );
        assertThat(verification.getFailedAttemptCount()).isEqualTo(1);
        assertThat(verification.getUsed()).isFalse();
    }

    @Test
    void confirmEmailVerificationLocksAfterMaxAttemptsEvenWithCorrectCode() {
        EmailVerification verification = verification("123456", false, LocalDateTime.now().plusMinutes(10));
        ReflectionTestUtils.setField(verification, "failedAttempts", AuthService.MAX_CODE_ATTEMPTS);
        when(emailVerificationRepository.findFirstByEmailOrderByIdDesc(EMAIL)).thenReturn(Optional.of(verification));

        assertErrorCode(
                () -> service.confirmEmailVerification(new EmailVerificationConfirmRequest(EMAIL, "123456")),
                ErrorCode.AUTH_TOO_MANY_ATTEMPTS
        );
        assertThat(verification.getUsed()).isFalse();
    }

    @Test
    void confirmEmailVerificationRejectsUsedCode() {
        when(emailVerificationRepository.findFirstByEmailOrderByIdDesc(EMAIL))
                .thenReturn(Optional.of(verification("123456", true, LocalDateTime.now().plusMinutes(10))));

        assertErrorCode(
                () -> service.confirmEmailVerification(new EmailVerificationConfirmRequest(EMAIL, "123456")),
                ErrorCode.AUTH_EMAIL_VERIFICATION_ALREADY_USED
        );
    }

    @Test
    void confirmEmailVerificationRejectsExpiredCode() {
        when(emailVerificationRepository.findFirstByEmailOrderByIdDesc(EMAIL))
                .thenReturn(Optional.of(verification("123456", false, LocalDateTime.now().minusMinutes(1))));

        assertErrorCode(
                () -> service.confirmEmailVerification(new EmailVerificationConfirmRequest(EMAIL, "123456")),
                ErrorCode.AUTH_EMAIL_VERIFICATION_EXPIRED
        );
    }

    @Test
    void confirmEmailVerificationMarksCodeUsed() {
        EmailVerification verification = verification("123456", false, LocalDateTime.now().plusMinutes(10));
        when(emailVerificationRepository.findFirstByEmailOrderByIdDesc(EMAIL)).thenReturn(Optional.of(verification));
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);

        service.confirmEmailVerification(new EmailVerificationConfirmRequest(EMAIL, "123456"));

        assertThat(verification.getUsed()).isTrue();
    }

    // ---- 비밀번호 재설정·변경 ----

    @Test
    void issuePasswordResetSendsSixDigitCodeToActiveUser() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(activeUser(1L)));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(activeUser(1L)));
        when(passwordResetTokenRepository.existsByToken(anyString())).thenReturn(false);
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.issuePasswordReset(new PasswordResetIssueRequest(EMAIL));

        verify(passwordResetTokenRepository, never()).deleteByEmailAndUsedFalse(anyString());
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendPasswordReset(eq(EMAIL), codeCaptor.capture());
        assertThat(codeCaptor.getValue()).matches("\\d{6}");
    }

    @Test
    void issuePasswordResetAnswersSameWhenMailFails() {
        // 메일 장애 때 가입된 이메일만 500이 나면 가입 여부가 드러난다.
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(activeUser(1L)));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(activeUser(1L)));
        when(passwordResetTokenRepository.existsByToken(anyString())).thenReturn(false);
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp down"))
                .when(mailService).sendPasswordReset(anyString(), anyString());

        var response = service.issuePasswordReset(new PasswordResetIssueRequest(EMAIL));

        assertThat(response.email()).isEqualTo(EMAIL);
        assertThat(response.expiresAt()).isAfter(LocalDateTime.now().plusMinutes(29));
        verify(passwordResetTokenRepository).save(any(PasswordResetToken.class));
    }

    @Test
    void issuePasswordResetDoesNotRecreateTokenWhenAccountWasDeletedAfterLookup() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(activeUser(1L)));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        var response=service.issuePasswordReset(new PasswordResetIssueRequest(EMAIL));

        assertThat(response.email()).isEqualTo(EMAIL);
        verify(passwordResetTokenRepository,never()).save(any());
        verify(mailService,never()).sendPasswordReset(anyString(),anyString());
    }

    @Test
    void issuePasswordResetAnswersSameForUnknownEmailWithoutSending() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        var response = service.issuePasswordReset(new PasswordResetIssueRequest(EMAIL));

        assertThat(response.email()).isEqualTo(EMAIL);
        assertThat(response.expiresAt()).isAfter(LocalDateTime.now().plusMinutes(29));
        verify(passwordResetTokenRepository, never()).save(any());
        verify(mailService, never()).sendPasswordReset(anyString(), anyString());
    }

    @Test
    void issuePasswordResetAnswersSameForInactiveUserWithoutSending() {
        User user = activeUser(1L);
        ReflectionTestUtils.setField(user, "status", UserStatus.withdrawn);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        var response = service.issuePasswordReset(new PasswordResetIssueRequest(EMAIL));

        assertThat(response.email()).isEqualTo(EMAIL);
        verify(mailService, never()).sendPasswordReset(anyString(), anyString());
    }

    @Test
    void issuePasswordResetSilentlySkipsResendWithinCooldown() {
        PasswordResetToken recent = resetToken("123456", false, LocalDateTime.now().plusMinutes(30));
        ReflectionTestUtils.setField(recent, "createdAt", LocalDateTime.now().minusSeconds(10));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(activeUser(1L)));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(activeUser(1L)));
        when(passwordResetTokenRepository.findFirstByEmailOrderByIdDesc(EMAIL)).thenReturn(Optional.of(recent));

        var response = service.issuePasswordReset(new PasswordResetIssueRequest(EMAIL));

        assertThat(response.email()).isEqualTo(EMAIL);
        verify(passwordResetTokenRepository, never()).save(any());
        verify(mailService, never()).sendPasswordReset(anyString(), anyString());
    }

    @Test
    void confirmPasswordResetRejectsWhenNoCodeIssued() {
        when(passwordResetTokenRepository.findFirstByEmailOrderByIdDesc(EMAIL)).thenReturn(Optional.empty());

        assertErrorCode(
                () -> service.confirmPasswordReset(new PasswordResetConfirmRequest(EMAIL, "000000", "newPassword123")),
                ErrorCode.AUTH_PASSWORD_RESET_NOT_FOUND
        );
    }

    @Test
    void confirmPasswordResetCountsWrongCodeAndKeepsPassword() {
        PasswordResetToken token = resetToken("123456", false, LocalDateTime.now().plusMinutes(10));
        when(passwordResetTokenRepository.findFirstByEmailOrderByIdDesc(EMAIL)).thenReturn(Optional.of(token));

        assertErrorCode(
                () -> service.confirmPasswordReset(new PasswordResetConfirmRequest(EMAIL, "000000", "newPassword123")),
                ErrorCode.AUTH_PASSWORD_RESET_NOT_FOUND
        );
        assertThat(token.getFailedAttemptCount()).isEqualTo(1);
        assertThat(token.getUsed()).isFalse();
        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    void confirmPasswordResetLocksAfterMaxAttemptsEvenWithCorrectCode() {
        PasswordResetToken token = resetToken("123456", false, LocalDateTime.now().plusMinutes(10));
        ReflectionTestUtils.setField(token, "failedAttempts", AuthService.MAX_CODE_ATTEMPTS);
        when(passwordResetTokenRepository.findFirstByEmailOrderByIdDesc(EMAIL)).thenReturn(Optional.of(token));

        assertErrorCode(
                () -> service.confirmPasswordReset(new PasswordResetConfirmRequest(EMAIL, "123456", "newPassword123")),
                ErrorCode.AUTH_TOO_MANY_ATTEMPTS
        );
        verify(sessionRepository, never()).deleteByUserId(any());
    }

    @Test
    void confirmPasswordResetTreatsLegacyNullAttemptsAsZero() {
        User user = activeUser(1L);
        PasswordResetToken token = resetToken("123456", false, LocalDateTime.now().plusMinutes(10));
        ReflectionTestUtils.setField(token, "failedAttempts", null);
        when(passwordResetTokenRepository.findFirstByEmailOrderByIdDesc(EMAIL)).thenReturn(Optional.of(token));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        service.confirmPasswordReset(new PasswordResetConfirmRequest(EMAIL, "123456", "newPassword123"));

        assertThat(token.getUsed()).isTrue();
    }

    @Test
    void confirmPasswordResetRejectsUsedCode() {
        when(passwordResetTokenRepository.findFirstByEmailOrderByIdDesc(EMAIL))
                .thenReturn(Optional.of(resetToken("123456", true, LocalDateTime.now().plusMinutes(10))));

        assertErrorCode(
                () -> service.confirmPasswordReset(new PasswordResetConfirmRequest(EMAIL, "123456", "newPassword123")),
                ErrorCode.AUTH_PASSWORD_RESET_ALREADY_USED
        );
    }

    @Test
    void confirmPasswordResetRejectsExpiredCode() {
        when(passwordResetTokenRepository.findFirstByEmailOrderByIdDesc(EMAIL))
                .thenReturn(Optional.of(resetToken("123456", false, LocalDateTime.now().minusMinutes(1))));

        assertErrorCode(
                () -> service.confirmPasswordReset(new PasswordResetConfirmRequest(EMAIL, "123456", "newPassword123")),
                ErrorCode.AUTH_PASSWORD_RESET_EXPIRED
        );
    }

    @Test
    void confirmPasswordResetChangesPasswordAndRevokesAllSessions() {
        User user = activeUser(1L);
        PasswordResetToken token = resetToken("123456", false, LocalDateTime.now().plusMinutes(10));
        when(passwordResetTokenRepository.findFirstByEmailOrderByIdDesc(EMAIL)).thenReturn(Optional.of(token));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        service.confirmPasswordReset(new PasswordResetConfirmRequest(EMAIL, "123456", "newPassword123"));

        assertThat(passwordHasher.matches("newPassword123", user.getPasswordHash())).isTrue();
        assertThat(passwordHasher.matches(PASSWORD, user.getPasswordHash())).isFalse();
        assertThat(token.getUsed()).isTrue();
        verify(sessionRepository).deleteByUserId(1L);
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() {
        Session current = session(1L, "refresh", LocalDateTime.now().plusDays(1));
        User user = activeUser(1L);
        String originalHash = user.getPasswordHash();
        when(sessionRepository.findByRefreshToken(AuthService.hashRefreshToken("refresh"))).thenReturn(Optional.of(current));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertErrorCode(
                () -> service.changePassword(new ChangePasswordRequest("refresh", "wrong-password", "newPassword123")),
                ErrorCode.USER_PASSWORD_INVALID
        );
        assertThat(user.getPasswordHash()).isEqualTo(originalHash);
    }

    @Test
    void changePasswordRejectsExpiredSession() {
        Session expired = session(1L, "refresh", LocalDateTime.now().minusMinutes(1));
        when(sessionRepository.findByRefreshToken(AuthService.hashRefreshToken("refresh"))).thenReturn(Optional.of(expired));

        assertErrorCode(
                () -> service.changePassword(new ChangePasswordRequest("refresh", PASSWORD, "newPassword123")),
                ErrorCode.AUTH_TOKEN_EXPIRED
        );
    }

    @Test
    void changePasswordUpdatesHash() {
        Session current = session(1L, "refresh", LocalDateTime.now().plusDays(1));
        User user = activeUser(1L);
        when(sessionRepository.findByRefreshToken(AuthService.hashRefreshToken("refresh"))).thenReturn(Optional.of(current));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.changePassword(new ChangePasswordRequest("refresh", PASSWORD, "newPassword123"));

        assertThat(passwordHasher.matches("newPassword123", user.getPasswordHash())).isTrue();
        verify(sessionRepository).deleteByUserId(1L);
        verify(sessionRepository, never()).delete(any(Session.class));
    }

    // ---- helpers ----

    private void assertErrorCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(expected);
    }

    private SignUpRequest signUpRequest() {
        return new SignUpRequest(EMAIL, PASSWORD, "학생", null, true, true, false);
    }

    private LoginRequest loginRequest(String password) {
        return new LoginRequest(EMAIL, password, "test-device");
    }

    private User activeUser(Long id) {
        User user = User.registerEmailUser(EMAIL, passwordHasher.hash(PASSWORD), "학생", null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Session session(Long userId, String refreshToken, LocalDateTime expiresAt) {
        return Session.issue(userId, refreshToken, null, expiresAt);
    }

    private EmailVerification verification(String code, boolean used, LocalDateTime expiresAt) {
        EmailVerification verification = EmailVerification.issue(EMAIL, code, expiresAt);
        if (used) {
            verification.markUsed();
        }
        return verification;
    }

    private PasswordResetToken resetToken(String code, boolean used, LocalDateTime expiresAt) {
        PasswordResetToken token = PasswordResetToken.issue(EMAIL, code, expiresAt);
        if (used) {
            token.markUsed();
        }
        return token;
    }
}
