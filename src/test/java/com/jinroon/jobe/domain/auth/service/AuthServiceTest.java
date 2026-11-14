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
 * 3.1 이식 전 기준선 테스트. 수정 후에도 유지해야 하는 인증 동작을 고정한다.
 * 이후 단계에서 바뀔 동작(재설정 요청의 미가입 이메일 응답, 비밀번호 변경 시 세션 정리 범위)은 여기서 고정하지 않는다.
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
                mailService
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
    }

    // ---- refresh / logout ----

    @Test
    void refreshRejectsUnknownToken() {
        when(sessionRepository.findByRefreshToken("unknown")).thenReturn(Optional.empty());

        assertErrorCode(() -> service.refresh(new RefreshTokenRequest("unknown", null)), ErrorCode.AUTH_TOKEN_INVALID);
    }

    @Test
    void refreshDeletesExpiredSession() {
        Session expired = session(1L, "old-token", LocalDateTime.now().minusMinutes(1));
        when(sessionRepository.findByRefreshToken("old-token")).thenReturn(Optional.of(expired));

        assertErrorCode(() -> service.refresh(new RefreshTokenRequest("old-token", null)), ErrorCode.AUTH_TOKEN_EXPIRED);
        verify(sessionRepository).delete(expired);
    }

    @Test
    void refreshRejectsInactiveUser() {
        Session current = session(1L, "old-token", LocalDateTime.now().plusDays(1));
        User user = activeUser(1L);
        ReflectionTestUtils.setField(user, "status", UserStatus.withdrawn);
        when(sessionRepository.findByRefreshToken("old-token")).thenReturn(Optional.of(current));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertErrorCode(() -> service.refresh(new RefreshTokenRequest("old-token", null)), ErrorCode.UNAUTHORIZED);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void refreshRotatesSession() {
        Session current = session(1L, "old-token", LocalDateTime.now().plusDays(1));
        when(sessionRepository.findByRefreshToken("old-token")).thenReturn(Optional.of(current));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser(1L)));
        when(sessionRepository.save(any(Session.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthResponse response = service.refresh(new RefreshTokenRequest("old-token", "new-device"));

        verify(sessionRepository).delete(current);
        assertThat(response.refreshToken()).isNotEqualTo("old-token");
        assertThat(jwtProvider.isValid(response.accessToken())).isTrue();
    }

    @Test
    void logoutDeletesSessionByRefreshToken() {
        service.logout(new LogoutRequest("some-token"));

        verify(sessionRepository).deleteByRefreshToken("some-token");
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
    void issueEmailVerificationReplacesUnusedCodeAndSendsSixDigitCode() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(emailVerificationRepository.save(any(EmailVerification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.issueEmailVerification(new EmailVerificationIssueRequest(EMAIL));

        verify(emailVerificationRepository).deleteByEmailAndUsedFalse(EMAIL);
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendEmailVerification(eq(EMAIL), codeCaptor.capture());
        assertThat(codeCaptor.getValue()).matches("\\d{6}");
        assertThat(response.email()).isEqualTo(EMAIL);
        assertThat(response.expiresAt()).isAfter(LocalDateTime.now().plusMinutes(29));
    }

    @Test
    void confirmEmailVerificationRejectsUnknownCode() {
        when(emailVerificationRepository.findByEmailAndToken(EMAIL, "000000")).thenReturn(Optional.empty());

        assertErrorCode(
                () -> service.confirmEmailVerification(new EmailVerificationConfirmRequest(EMAIL, "000000")),
                ErrorCode.AUTH_EMAIL_VERIFICATION_NOT_FOUND
        );
    }

    @Test
    void confirmEmailVerificationRejectsUsedCode() {
        when(emailVerificationRepository.findByEmailAndToken(EMAIL, "123456"))
                .thenReturn(Optional.of(verification("123456", true, LocalDateTime.now().plusMinutes(10))));

        assertErrorCode(
                () -> service.confirmEmailVerification(new EmailVerificationConfirmRequest(EMAIL, "123456")),
                ErrorCode.AUTH_EMAIL_VERIFICATION_ALREADY_USED
        );
    }

    @Test
    void confirmEmailVerificationRejectsExpiredCode() {
        when(emailVerificationRepository.findByEmailAndToken(EMAIL, "123456"))
                .thenReturn(Optional.of(verification("123456", false, LocalDateTime.now().minusMinutes(1))));

        assertErrorCode(
                () -> service.confirmEmailVerification(new EmailVerificationConfirmRequest(EMAIL, "123456")),
                ErrorCode.AUTH_EMAIL_VERIFICATION_EXPIRED
        );
    }

    @Test
    void confirmEmailVerificationMarksCodeUsed() {
        EmailVerification verification = verification("123456", false, LocalDateTime.now().plusMinutes(10));
        when(emailVerificationRepository.findByEmailAndToken(EMAIL, "123456")).thenReturn(Optional.of(verification));
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);

        service.confirmEmailVerification(new EmailVerificationConfirmRequest(EMAIL, "123456"));

        assertThat(verification.getUsed()).isTrue();
    }

    // ---- 비밀번호 재설정·변경 ----

    @Test
    void issuePasswordResetSendsSixDigitCodeToActiveUser() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(activeUser(1L)));
        when(passwordResetTokenRepository.existsByToken(anyString())).thenReturn(false);
        when(passwordResetTokenRepository.save(any(PasswordResetToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.issuePasswordReset(new PasswordResetIssueRequest(EMAIL));

        verify(passwordResetTokenRepository).deleteByEmailAndUsedFalse(EMAIL);
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendPasswordReset(eq(EMAIL), codeCaptor.capture());
        assertThat(codeCaptor.getValue()).matches("\\d{6}");
    }

    @Test
    void confirmPasswordResetRejectsUnknownCode() {
        when(passwordResetTokenRepository.findByEmailAndToken(EMAIL, "000000")).thenReturn(Optional.empty());

        assertErrorCode(
                () -> service.confirmPasswordReset(new PasswordResetConfirmRequest(EMAIL, "000000", "newPassword123")),
                ErrorCode.AUTH_PASSWORD_RESET_NOT_FOUND
        );
    }

    @Test
    void confirmPasswordResetRejectsUsedCode() {
        when(passwordResetTokenRepository.findByEmailAndToken(EMAIL, "123456"))
                .thenReturn(Optional.of(resetToken("123456", true, LocalDateTime.now().plusMinutes(10))));

        assertErrorCode(
                () -> service.confirmPasswordReset(new PasswordResetConfirmRequest(EMAIL, "123456", "newPassword123")),
                ErrorCode.AUTH_PASSWORD_RESET_ALREADY_USED
        );
    }

    @Test
    void confirmPasswordResetRejectsExpiredCode() {
        when(passwordResetTokenRepository.findByEmailAndToken(EMAIL, "123456"))
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
        when(passwordResetTokenRepository.findByEmailAndToken(EMAIL, "123456")).thenReturn(Optional.of(token));
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
        when(sessionRepository.findByRefreshToken("refresh")).thenReturn(Optional.of(current));
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
        when(sessionRepository.findByRefreshToken("refresh")).thenReturn(Optional.of(expired));

        assertErrorCode(
                () -> service.changePassword(new ChangePasswordRequest("refresh", PASSWORD, "newPassword123")),
                ErrorCode.AUTH_TOKEN_EXPIRED
        );
    }

    @Test
    void changePasswordUpdatesHash() {
        Session current = session(1L, "refresh", LocalDateTime.now().plusDays(1));
        User user = activeUser(1L);
        when(sessionRepository.findByRefreshToken("refresh")).thenReturn(Optional.of(current));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        service.changePassword(new ChangePasswordRequest("refresh", PASSWORD, "newPassword123"));

        assertThat(passwordHasher.matches("newPassword123", user.getPasswordHash())).isTrue();
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
