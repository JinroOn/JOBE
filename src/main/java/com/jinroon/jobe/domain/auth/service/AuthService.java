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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
    static final int MAX_CODE_ATTEMPTS = 5;
    static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    static final Duration ISSUE_WINDOW = Duration.ofHours(1);
    static final int MAX_ISSUES_PER_WINDOW = 5;

    private final UserRepository userRepository;
    private final UserConsentRepository userConsentRepository;
    private final SessionRepository sessionRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordHasher passwordHasher;
    private final JwtProvider jwtProvider;
    private final MailService mailService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final com.jinroon.jobe.domain.user.service.UserWriteGuard writeGuard;

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
        try {
            // IDENTITY 전략이라 save 시점에 INSERT가 실행되어 유일 제약 위반이 여기서 드러난다.
            userRepository.save(user);
        } catch (DataIntegrityViolationException exception) {
            // existsByEmail 확인 뒤 같은 이메일이 동시에 가입된 경우(uk_users_email 위반)
            throw new CustomException(ErrorCode.USER_EMAIL_DUPLICATE);
        }
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
        IssuedSession issued = issueSession(user, request.deviceInfo());
        String accessToken = jwtProvider.createToken(user);
        return new AuthResponse(UserResponse.from(user), accessToken, issued.rawRefreshToken(), issued.session().getExpiresAt());
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        Session currentSession = sessionRepository.findByRefreshToken(hashRefreshToken(request.refreshToken()))
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_TOKEN_INVALID));
        if (currentSession.isExpired(LocalDateTime.now())) {
            sessionRepository.delete(currentSession);
            throw new CustomException(ErrorCode.AUTH_TOKEN_EXPIRED);
        }
        User user = userRepository.findById(currentSession.getUserId())
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_TOKEN_INVALID));
        requireActive(user);
        // 같은 토큰으로 동시에 갱신하면 먼저 지운 요청만 새 토큰을 받는다. 나머지는 이미 쓰인 토큰으로 본다.
        if (sessionRepository.deleteSessionById(currentSession.getId()) == 0) {
            throw new CustomException(ErrorCode.AUTH_TOKEN_INVALID);
        }
        IssuedSession issued = issueSession(user, request.deviceInfo());
        String accessToken = jwtProvider.createToken(user);
        return new AuthResponse(UserResponse.from(user), accessToken, issued.rawRefreshToken(), issued.session().getExpiresAt());
    }

    @Transactional
    public void logout(LogoutRequest request) {
        sessionRepository.deleteByRefreshToken(hashRefreshToken(request.refreshToken()));
    }

    @Transactional
    public EmailVerificationResponse issueEmailVerification(EmailVerificationIssueRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new CustomException(ErrorCode.USER_EMAIL_DUPLICATE);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean allowed = isIssueAllowed(
                emailVerificationRepository.findFirstByEmailOrderByIdDesc(request.email())
                        .map(EmailVerification::getCreatedAt)
                        .orElse(null),
                emailVerificationRepository.countByEmailAndCreatedAtAfter(request.email(), now.minus(ISSUE_WINDOW)),
                now
        );
        if (!allowed) {
            throw new CustomException(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        }
        // 이전 미사용 코드는 지우지 않는다(발급 횟수 집계용). 확인은 항상 가장 최근 코드로만 한다.
        LocalDateTime expiresAt = now.plusMinutes(EMAIL_TOKEN_MINUTES);
        EmailVerification verification = emailVerificationRepository.save(
                EmailVerification.issue(request.email(), issueEmailVerificationCode(), expiresAt)
        );
        mailService.sendEmailVerification(verification.getEmail(), verification.getToken());
        return EmailVerificationResponse.from(verification);
    }

    // 틀린 코드의 실패 횟수가 예외와 함께 롤백되지 않도록 CustomException에서는 커밋한다.
    // 이 메서드에서 예외 전에 바뀌는 상태는 실패 횟수뿐이다.
    @Transactional(noRollbackFor = CustomException.class)
    public EmailVerificationResponse confirmEmailVerification(EmailVerificationConfirmRequest request) {
        EmailVerification verification = emailVerificationRepository.findFirstByEmailOrderByIdDesc(request.email())
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_EMAIL_VERIFICATION_NOT_FOUND));
        if (verification.getUsed()) {
            throw new CustomException(ErrorCode.AUTH_EMAIL_VERIFICATION_ALREADY_USED);
        }
        if (verification.isExpired(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.AUTH_EMAIL_VERIFICATION_EXPIRED);
        }
        if (verification.isAttemptLimitReached(MAX_CODE_ATTEMPTS)) {
            throw new CustomException(ErrorCode.AUTH_TOO_MANY_ATTEMPTS);
        }
        if (!verification.matchesCode(request.token())) {
            verification.recordFailedAttempt();
            throw new CustomException(ErrorCode.AUTH_EMAIL_VERIFICATION_NOT_FOUND);
        }
        if (userRepository.existsByEmail(verification.getEmail())) {
            throw new CustomException(ErrorCode.USER_EMAIL_DUPLICATE);
        }
        verification.markUsed();
        return EmailVerificationResponse.from(verification);
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        Session session = sessionRepository.findByRefreshToken(hashRefreshToken(request.refreshToken()))
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
        // 다른 기기의 세션도 모두 끊는다(비밀번호 재설정과 같은 정책).
        sessionRepository.deleteByUserId(user.getId());
    }

    /**
     * 가입 여부를 드러내지 않기 위해 미가입·비활성 이메일과 발급 제한에 걸린 요청에도 같은 성공 응답을 준다.
     * 이 경우 코드는 만들지 않고 메일도 보내지 않는다.
     */
    @Transactional
    public PasswordResetResponse issuePasswordReset(PasswordResetIssueRequest request) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(PASSWORD_RESET_TOKEN_MINUTES);
        PasswordResetResponse silentResponse = new PasswordResetResponse(request.email(), expiresAt);

        User user = userRepository.findByEmail(request.email()).orElse(null);
        if (user == null || !user.isActive()) {
            return silentResponse;
        }
        // Serialize token issuance with account deletion while keeping the non-enumerating response.
        user = userRepository.findByIdForUpdate(user.getId()).orElse(null);
        if (user == null || !user.isActive()) {
            return silentResponse;
        }
        boolean allowed = isIssueAllowed(
                passwordResetTokenRepository.findFirstByEmailOrderByIdDesc(user.getEmail())
                        .map(PasswordResetToken::getCreatedAt)
                        .orElse(null),
                passwordResetTokenRepository.countByEmailAndCreatedAtAfter(user.getEmail(), now.minus(ISSUE_WINDOW)),
                now
        );
        if (!allowed) {
            return silentResponse;
        }

        // 이전 미사용 코드는 지우지 않는다(발급 횟수 집계용). 확인은 항상 가장 최근 코드로만 한다.
        PasswordResetToken resetToken = passwordResetTokenRepository.save(
                PasswordResetToken.issue(user.getEmail(), issueUniquePasswordResetCode(), expiresAt)
        );
        mailService.sendPasswordReset(resetToken.getEmail(), resetToken.getToken());
        return PasswordResetResponse.from(resetToken);
    }

    // 틀린 코드의 실패 횟수가 예외와 함께 롤백되지 않도록 CustomException에서는 커밋한다.
    // 이 메서드에서 예외 전에 바뀌는 상태는 실패 횟수뿐이다.
    @Transactional(noRollbackFor = CustomException.class)
    public void confirmPasswordReset(PasswordResetConfirmRequest request) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findFirstByEmailOrderByIdDesc(request.email())
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_PASSWORD_RESET_NOT_FOUND));
        if (resetToken.getUsed()) {
            throw new CustomException(ErrorCode.AUTH_PASSWORD_RESET_ALREADY_USED);
        }
        if (resetToken.isExpired(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.AUTH_PASSWORD_RESET_EXPIRED);
        }
        if (resetToken.isAttemptLimitReached(MAX_CODE_ATTEMPTS)) {
            throw new CustomException(ErrorCode.AUTH_TOO_MANY_ATTEMPTS);
        }
        if (!resetToken.matchesCode(request.code())) {
            resetToken.recordFailedAttempt();
            throw new CustomException(ErrorCode.AUTH_PASSWORD_RESET_NOT_FOUND);
        }

        User user = userRepository.findByEmail(resetToken.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        requireActive(user);
        user.changePassword(passwordHasher.hash(request.newPassword()));
        resetToken.markUsed();
        sessionRepository.deleteByUserId(user.getId());
    }

    /**
     * refresh 토큰 원문은 응답으로만 내보내고 DB에는 SHA-256 해시만 저장한다.
     * 토큰은 48바이트 난수라 솔트 없는 단순 해시로 충분하다(비밀번호처럼 추측 공격 대상이 아님).
     */
    private IssuedSession issueSession(User user, String deviceInfo) {
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(REFRESH_TOKEN_DAYS);
        String rawToken = issueToken(REFRESH_TOKEN_BYTES);
        Session session = sessionRepository.save(
                Session.issue(user.getId(), hashRefreshToken(rawToken), deviceInfo, expiresAt));
        return new IssuedSession(session, rawToken);
    }

    static String hashRefreshToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record IssuedSession(Session session, String rawRefreshToken) {
    }

    private boolean isIssueAllowed(LocalDateTime lastIssuedAt, long issuedInWindow, LocalDateTime now) {
        if (lastIssuedAt != null && lastIssuedAt.isAfter(now.minus(RESEND_COOLDOWN))) {
            return false;
        }
        return issuedInWindow < MAX_ISSUES_PER_WINDOW;
    }

    private void requireActive(User user) {
        writeGuard.requireActive(user.getId());
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
