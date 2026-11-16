package com.jinroon.jobe.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinroon.jobe.domain.auth.dto.request.LoginRequest;
import com.jinroon.jobe.domain.auth.dto.request.RefreshTokenRequest;
import com.jinroon.jobe.domain.auth.dto.response.AuthResponse;
import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.repository.SessionRepository;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 같은 refresh 토큰으로 동시에 여러 번 갱신해도 새 세션은 하나만 생기는지 실제 트랜잭션으로 확인한다.
 * 토큰이 탈취돼 원래 사용자와 동시에 쓰이는 경우 둘 다 새 토큰을 받으면 안 된다.
 * 로컬 MySQL(docker compose -f docker-compose.test.yml up -d)이 필요하다. 테스트 클래스 트랜잭션을 쓰지 않으므로 직접 정리한다.
 */
@SpringBootTest
class ConcurrentRefreshIntegrationTest {

    private static final int CONCURRENT_REQUESTS = 5;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    private final String email = "concurrent-refresh-" + UUID.randomUUID() + "@example.com";
    private User savedUser;

    @AfterEach
    void cleanUp() {
        if (savedUser != null) {
            sessionRepository.deleteAll(sessionRepository.findByUserId(savedUser.getId()));
            userRepository.deleteById(savedUser.getId());
        }
    }

    @Test
    void sameRefreshTokenUsedConcurrentlyIssuesOnlyOneNewSession() throws Exception {
        savedUser = userRepository.save(
                User.registerEmailUser(email, passwordHasher.hash("password123"), "테스트", null));
        String refreshToken = authService.login(new LoginRequest(email, "password123", "test")).refreshToken();

        List<Outcome> outcomes = refreshConcurrently(refreshToken);

        List<Outcome> successes = outcomes.stream().filter(Outcome::succeeded).toList();
        assertThat(successes).as("outcomes %s", outcomes).hasSize(1);
        assertThat(sessionRepository.findByUserId(savedUser.getId())).hasSize(1);
        // 나머지는 500(낙관적 락 오류)이 아니라 401(이미 쓰인 토큰)이어야 한다.
        assertThat(outcomes).filteredOn(o -> !o.succeeded())
                .allSatisfy(o -> assertThat(o.errorCode()).isEqualTo(ErrorCode.AUTH_TOKEN_INVALID));
    }

    private List<Outcome> refreshConcurrently(String refreshToken) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                Callable<Outcome> call = () -> {
                    ready.countDown();
                    start.await();
                    try {
                        AuthResponse response = authService.refresh(new RefreshTokenRequest(refreshToken, "test"));
                        return Outcome.success(response.refreshToken());
                    } catch (CustomException exception) {
                        return Outcome.failure(exception.getErrorCode(), exception.getClass().getSimpleName());
                    } catch (RuntimeException exception) {
                        return Outcome.failure(null, exception.getClass().getSimpleName());
                    }
                };
                futures.add(executor.submit(call));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private record Outcome(boolean succeeded, String refreshToken, ErrorCode errorCode, String exceptionType) {

        static Outcome success(String refreshToken) {
            return new Outcome(true, refreshToken, null, null);
        }

        static Outcome failure(ErrorCode errorCode, String exceptionType) {
            return new Outcome(false, null, errorCode, exceptionType);
        }

        @Override
        public String toString() {
            return succeeded ? "OK" : exceptionType + "(" + errorCode + ")";
        }
    }
}
