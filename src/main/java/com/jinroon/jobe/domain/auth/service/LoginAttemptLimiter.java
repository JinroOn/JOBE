package com.jinroon.jobe.domain.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 같은 이메일로 로그인 실패가 반복되면 일정 시간 로그인을 막는다(비밀번호 대입 방지).
 * 가입 여부를 드러내지 않도록 없는 이메일도 같은 규칙을 적용한다. 단일 서버 메모리 기준이며 재시작하면 초기화된다.
 */
@Component
public class LoginAttemptLimiter {
    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_TRACKED = 10_000;

    private final Clock clock;
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptLimiter() {
        this(Clock.systemUTC());
    }

    LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 실패 한도에 도달해 아직 막혀 있으면 true. */
    public boolean isBlocked(String email) {
        Failures current = failures.get(key(email));
        if (current == null) return false;
        if (current.expired(clock.instant())) {
            failures.remove(key(email), current);
            return false;
        }
        return current.count() >= MAX_FAILURES;
    }

    public void recordFailure(String email) {
        Instant now = clock.instant();
        if (failures.size() >= MAX_TRACKED) failures.values().removeIf(f -> f.expired(now));
        failures.compute(key(email), (k, current) ->
                current == null || current.expired(now) ? new Failures(now, 1) : new Failures(current.firstAt(), current.count() + 1));
    }

    public void recordSuccess(String email) {
        failures.remove(key(email));
    }

    private static String key(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private record Failures(Instant firstAt, int count) {
        boolean expired(Instant now) {
            return !now.isBefore(firstAt.plus(WINDOW));
        }
    }
}
