package com.jinroon.jobe.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jinroon.jobe.domain.auth.dto.request.LoginRequest;
import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.repository.SessionRepository;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * access 토큰이 만료·무효인 상태에서도 refresh 토큰만으로 로그아웃해 서버 세션을 지울 수 있는지 확인한다.
 * 예전에는 로그아웃이 인증 경로라 401이 나고 refresh 토큰이 계속 유효했다.
 * 로컬 MySQL(docker compose -f docker-compose.test.yml up -d)이 필요하다. 테스트 데이터는 트랜잭션 롤백으로 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LogoutWithoutAccessTokenHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private PasswordHasher passwordHasher;

    @Test
    void logoutWithRefreshTokenOnlyRevokesSession() throws Exception {
        String email = "logout-" + UUID.randomUUID() + "@example.com";
        User user = userRepository.save(User.registerEmailUser(email, passwordHasher.hash("password123"), "테스트", null));
        String refreshToken = authService.login(new LoginRequest(email, "password123", "test")).refreshToken();
        String body = "{\"refreshToken\":\"" + refreshToken + "\"}";

        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());

        assertThat(sessionRepository.findByUserId(user.getId())).isEmpty();
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    void logoutWithUnknownTokenStillSucceedsQuietly() throws Exception {
        mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"not-a-real-token\"}"))
                .andExpect(status().isNoContent());
    }
}
