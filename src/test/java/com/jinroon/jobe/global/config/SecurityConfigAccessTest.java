package com.jinroon.jobe.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.enums.UserEnums.UserRole;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.global.security.JwtProvider;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 3.1 이식 전 기준선 테스트: SecurityConfig의 공개·인증·관리자 경로 규칙.
 * 로컬 MySQL(docker compose up -d mysql)이 필요하다. 테스트 데이터는 트랜잭션 롤백으로 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityConfigAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtProvider jwtProvider;

    // ---- 비로그인 공개 경로 ----

    @Test
    void majorsArePublic() throws Exception {
        mockMvc.perform(get("/api/majors")).andExpect(status().isOk());
    }

    @Test
    void noticesArePublic() throws Exception {
        mockMvc.perform(get("/api/notices")).andExpect(status().isOk());
    }

    @Test
    void authEndpointsArePublic() throws Exception {
        // 인증 없이 통과해 요청 검증(400)까지 도달해야 한다.
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sharedResultPathIsPublic() throws Exception {
        mockMvc.perform(get("/api/results/share/unknown-token"))
                .andExpect(status().isNotFound());
    }

    // ---- 인증 필요 경로 ----

    @Test
    void protectedPathWithoutTokenReturns401Json() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void protectedPathWithInvalidTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedPathWithMemberTokenSucceeds() throws Exception {
        User member = saveUser(UserRole.member);

        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(member.getEmail()));
    }

    // ---- 관리자 전용 경로 ----

    @Test
    void adminUserListRejectsMember() throws Exception {
        User member = saveUser(UserRole.member);

        mockMvc.perform(get("/api/users").header("Authorization", bearer(member)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void adminUserListAllowsAdmin() throws Exception {
        User admin = saveUser(UserRole.admin);

        mockMvc.perform(get("/api/users").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void majorCreateRejectsMember() throws Exception {
        User member = saveUser(UserRole.member);

        mockMvc.perform(post("/api/majors")
                        .header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void noticeCreateRejectsMember() throws Exception {
        User member = saveUser(UserRole.member);

        mockMvc.perform(post("/api/notices")
                        .header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    private User saveUser(UserRole role) {
        String email = "security-test-" + UUID.randomUUID() + "@example.com";
        User user = User.registerEmailUser(email, "pbkdf2$1$c2FsdA==$aGFzaA==", "테스트", null);
        ReflectionTestUtils.setField(user, "role", role);
        return userRepository.save(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtProvider.createToken(user);
    }
}
