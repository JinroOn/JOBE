package com.jinroon.jobe.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.global.security.JwtProvider;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 3.2 1단계: 잘못된 요청이 500 대신 알맞은 4xx와 ErrorDto 형식으로 응답하는지 확인한다.
 * 로컬 MySQL(docker compose up -d mysql)이 필요하다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ErrorResponseHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtProvider jwtProvider;

    private String token;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.registerEmailUser(
                "error-test-" + UUID.randomUUID() + "@example.com", "pbkdf2$1$c2FsdA==$aGFzaA==", "테스트", null));
        token = "Bearer " + jwtProvider.createToken(user);
    }

    @Test
    void unknownPathReturns404() throws Exception {
        mockMvc.perform(get("/api/does-not-exist").header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void removedSessionCreationEndpointReturns404() throws Exception {
        mockMvc.perform(post("/api/users/sessions")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unsupportedMethodReturns405() throws Exception {
        mockMvc.perform(put("/api/users/me")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/users/favorites")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void wrongPathVariableTypeReturns400() throws Exception {
        mockMvc.perform(get("/api/results/not-a-number").header("Authorization", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedUnknownPathStillReturns401() throws Exception {
        // 인증이 먼저 걸리므로 비로그인 사용자에게 경로 존재 여부를 드러내지 않는다.
        mockMvc.perform(get("/api/does-not-exist")).andExpect(status().isUnauthorized());
    }
}
