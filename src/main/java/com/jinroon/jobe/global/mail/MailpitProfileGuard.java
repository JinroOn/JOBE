package com.jinroon.jobe.global.mail;

import java.util.Arrays;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * mailpit 프로필은 로컬 메일 확인 전용이다. 운영 프로필과 함께 켜지면
 * 인증 메일이 실제 사용자에게 가지 않고 로컬 포트로 빠지므로 시작을 막는다.
 */
@Component
@Profile("mailpit")
public class MailpitProfileGuard {

    public MailpitProfileGuard(Environment environment) {
        boolean production = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production"));
        if (production) {
            throw new IllegalStateException("mailpit 프로필은 운영(prod) 프로필과 함께 사용할 수 없습니다.");
        }
    }
}
