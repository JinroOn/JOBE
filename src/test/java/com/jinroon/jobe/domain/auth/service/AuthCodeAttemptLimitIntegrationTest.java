package com.jinroon.jobe.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.jinroon.jobe.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.jinroon.jobe.domain.auth.dto.request.EmailVerificationIssueRequest;
import com.jinroon.jobe.domain.auth.dto.request.PasswordResetConfirmRequest;
import com.jinroon.jobe.domain.auth.dto.request.PasswordResetIssueRequest;
import com.jinroon.jobe.domain.auth.entity.PasswordResetToken;
import com.jinroon.jobe.domain.auth.repository.PasswordResetTokenRepository;
import com.jinroon.jobe.domain.user.entity.EmailVerification;
import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.repository.EmailVerificationRepository;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.global.mail.MailService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 틀린 코드의 실패 횟수가 예외가 나도 DB에 남는지(롤백되지 않는지) 실제 트랜잭션으로 확인한다.
 * 단위 테스트는 트랜잭션을 거치지 않아 이 부분을 검증할 수 없다.
 * 로컬 MySQL(docker compose -f docker-compose.test.yml up -d)이 필요하다. 테스트 클래스 트랜잭션을 쓰지 않으므로 직접 정리한다.
 */
@SpringBootTest
class AuthCodeAttemptLimitIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private EmailVerificationRepository emailVerificationRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    @MockitoBean
    private MailService mailService;

    private final String email = "attempt-limit-" + UUID.randomUUID() + "@example.com";
    private User savedUser;

    @AfterEach
    void cleanUp() {
        passwordResetTokenRepository.findAll().stream()
                .filter(token -> email.equals(token.getEmail()))
                .forEach(passwordResetTokenRepository::delete);
        emailVerificationRepository.findAll().stream()
                .filter(verification -> email.equals(verification.getEmail()))
                .forEach(emailVerificationRepository::delete);
        if (savedUser != null) {
            userRepository.deleteById(savedUser.getId());
        }
    }

    @Test
    void passwordResetFailedAttemptsPersistAndLockCode() {
        savedUser = userRepository.save(
                User.registerEmailUser(email, passwordHasher.hash("password123"), "테스트", null));
        authService.issuePasswordReset(new PasswordResetIssueRequest(email));
        String code = sentPasswordResetCode();
        String wrongCode = differentCode(code);

        for (int i = 0; i < AuthService.MAX_CODE_ATTEMPTS; i++) {
            assertThatThrownBy(() -> authService.confirmPasswordReset(
                    new PasswordResetConfirmRequest(email, wrongCode, "newPassword123")))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.AUTH_PASSWORD_RESET_NOT_FOUND);
        }

        PasswordResetToken stored = passwordResetTokenRepository.findFirstByEmailOrderByIdDesc(email).orElseThrow();
        assertThat(stored.getFailedAttemptCount()).isEqualTo(AuthService.MAX_CODE_ATTEMPTS);

        assertThatThrownBy(() -> authService.confirmPasswordReset(
                new PasswordResetConfirmRequest(email, code, "newPassword123")))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AUTH_TOO_MANY_ATTEMPTS);

        User reloaded = userRepository.findById(savedUser.getId()).orElseThrow();
        assertThat(passwordHasher.matches("password123", reloaded.getPasswordHash())).isTrue();
    }

    @Test
    void emailVerificationFailedAttemptsPersistAndLockCode() {
        authService.issueEmailVerification(new EmailVerificationIssueRequest(email));
        String code = sentEmailVerificationCode();
        String wrongCode = differentCode(code);

        for (int i = 0; i < AuthService.MAX_CODE_ATTEMPTS; i++) {
            assertThatThrownBy(() -> authService.confirmEmailVerification(
                    new EmailVerificationConfirmRequest(email, wrongCode)))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.AUTH_EMAIL_VERIFICATION_NOT_FOUND);
        }

        EmailVerification stored = emailVerificationRepository.findFirstByEmailOrderByIdDesc(email).orElseThrow();
        assertThat(stored.getFailedAttemptCount()).isEqualTo(AuthService.MAX_CODE_ATTEMPTS);
        assertThat(stored.getUsed()).isFalse();

        assertThatThrownBy(() -> authService.confirmEmailVerification(
                new EmailVerificationConfirmRequest(email, code)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AUTH_TOO_MANY_ATTEMPTS);
    }

    @Test
    void passwordResetResendWithinCooldownDoesNotIssueNewCode() {
        savedUser = userRepository.save(
                User.registerEmailUser(email, passwordHasher.hash("password123"), "테스트", null));
        authService.issuePasswordReset(new PasswordResetIssueRequest(email));
        Long firstId = passwordResetTokenRepository.findFirstByEmailOrderByIdDesc(email).orElseThrow().getId();

        authService.issuePasswordReset(new PasswordResetIssueRequest(email));

        assertThat(passwordResetTokenRepository.findFirstByEmailOrderByIdDesc(email).orElseThrow().getId())
                .isEqualTo(firstId);
    }

    private String sentPasswordResetCode() {
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendPasswordReset(eq(email), codeCaptor.capture());
        return codeCaptor.getValue();
    }

    private String sentEmailVerificationCode() {
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendEmailVerification(eq(email), codeCaptor.capture());
        return codeCaptor.getValue();
    }

    private String differentCode(String code) {
        return "000000".equals(code) ? "111111" : "000000";
    }
}
