package com.jinroon.jobe.domain.auth.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 로그인 반복 실패가 실제 API에서 429와 ErrorDto 형식으로 막히는지 확인한다.
 * 다른 테스트와 겹치지 않도록 매번 새 이메일을 쓴다. 로컬 MySQL(docker compose -f docker-compose.test.yml up -d)이 필요하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginAttemptLimitHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void repeatedFailedLoginsReturn429() throws Exception {
        String body = "{\"email\":\"login-limit-" + UUID.randomUUID() + "@example.com\",\"password\":\"Wrong1234!\"}";

        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("AUTH_TOO_MANY_LOGIN_ATTEMPTS"))
                .andExpect(jsonPath("$.message").exists());
    }
}
