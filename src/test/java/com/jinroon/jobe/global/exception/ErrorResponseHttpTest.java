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
 * 잘못된 요청이 500 대신 알맞은 4xx와 ErrorDto 형식(status·code·message)으로 응답하는지 확인한다.
 * 로컬 MySQL(docker compose -f docker-compose.test.yml up -d)이 필요하다.
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
    void firewallRejectedPathReturns400InsteadOf401() throws Exception {
        // 공개 API라도 세미콜론이 든 주소는 방화벽이 거부한다. 인증 오류(401)로 보이지 않아야 한다.
        mockMvc.perform(get("/api/majors;x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/api/exploration/v1/shared-results/abc;x").header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void unknownPathReturns404() throws Exception {
        mockMvc.perform(get("/api/does-not-exist").header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists());
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
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/users/favorites")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void validationErrorUsesSameFormatWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void nonValidationErrorHasNoFieldErrorsKey() throws Exception {
        mockMvc.perform(get("/api/notices/999999999").header("Authorization", token))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void wrongPathVariableTypeReturns400() throws Exception {
        mockMvc.perform(get("/api/results/not-a-number").header("Authorization", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedUnknownPathStillReturns401() throws Exception {
        // 인증이 먼저 걸리므로 비로그인 사용자에게 경로 존재 여부를 드러내지 않는다.
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void forbiddenResponseHasCode() throws Exception {
        mockMvc.perform(get("/api/users").header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void customExceptionResponseHasCode() throws Exception {
        mockMvc.perform(get("/api/notices/999999999").header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTICE_NOT_FOUND"));
    }
}
