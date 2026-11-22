package com.jinroon.jobe.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jinroon.jobe.domain.notice.entity.Notice;
import com.jinroon.jobe.domain.notice.enums.NoticeEnums.NoticeDisplayType;
import com.jinroon.jobe.domain.notice.repository.NoticeRepository;
import com.jinroon.jobe.domain.user.entity.Session;
import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.enums.UserEnums.UserRole;
import com.jinroon.jobe.domain.user.enums.UserEnums.UserStatus;
import com.jinroon.jobe.domain.user.repository.SessionRepository;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.global.security.JwtProvider;
import java.time.LocalDateTime;
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
 * 로컬 MySQL(docker compose -f docker-compose.test.yml up -d)이 필요하다. 테스트 데이터는 트랜잭션 롤백으로 지운다.
 */
@SpringBootTest(properties = {"diagnosis.legacy-enabled=true", "diagnosis.questions.seed.enabled=true"})
@AutoConfigureMockMvc
@Transactional
class SecurityConfigAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private NoticeRepository noticeRepository;

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

    // ---- 3단계: 토큰의 사용자 상태 확인 ----

    @Test
    void suspendedUserTokenIsRejected() throws Exception {
        User user = saveUser(UserRole.member);
        String token = bearer(user);
        ReflectionTestUtils.setField(user, "status", UserStatus.suspended);
        userRepository.saveAndFlush(user);

        mockMvc.perform(get("/api/users/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenForMissingUserReturns401Not500() throws Exception {
        User ghost = User.registerEmailUser("ghost-" + UUID.randomUUID() + "@example.com", "x", "유령", null);
        ReflectionTestUtils.setField(ghost, "id", Long.MAX_VALUE);

        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(ghost)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithMismatchedUserIdIsRejected() throws Exception {
        User user = saveUser(UserRole.member);
        // 같은 이메일로 재가입한 다른 계정을 흉내낸다: 이메일은 같고 userId만 다른 토큰.
        User previousAccount = User.registerEmailUser(user.getEmail(), "x", "이전 계정", null);
        ReflectionTestUtils.setField(previousAccount, "id", user.getId() + 100_000);

        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(previousAccount)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sessionCreationEndpointIsRemoved() throws Exception {
        User member = saveUser(UserRole.member);
        long before = sessionRepository.findByUserId(member.getId()).size();

        mockMvc.perform(post("/api/users/sessions")
                        .header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"attacker-chosen\",\"expiresAt\":\"2099-01-01T00:00:00\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(201));
        assertThat(sessionRepository.findByUserId(member.getId())).hasSize((int) before);
    }

    @Test
    void sessionListDoesNotExposeRefreshToken() throws Exception {
        User member = saveUser(UserRole.member);
        sessionRepository.save(Session.issue(member.getId(), "stored-hash", "test-device",
                LocalDateTime.now().plusDays(1)));

        mockMvc.perform(get("/api/users/me/sessions").header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceInfo").value("test-device"))
                .andExpect(jsonPath("$[0].refreshToken").doesNotExist());
    }

    // ---- 5단계: 본인 프로필 수정 필드 제한 (문서 4.1) ----

    @Test
    void memberCannotPromoteSelfViaProfileUpdate() throws Exception {
        User member = saveUser(UserRole.member);

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"바뀐닉네임\",\"role\":\"admin\",\"status\":\"active\","
                                + "\"email\":\"other@example.com\",\"passwordHash\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("바뀐닉네임"))
                .andExpect(jsonPath("$.role").value("member"))
                .andExpect(jsonPath("$.email").value(member.getEmail()));

        User reloaded = userRepository.findById(member.getId()).orElseThrow();
        assertThat(reloaded.getRoleName()).isEqualTo("member");
        assertThat(reloaded.getPasswordHash()).isNotEqualTo("x");
    }

    // ---- 5단계: 공지 노출 범위 ----

    @Test
    void anonymousCannotSeeScheduledNotice() throws Exception {
        Notice scheduled = noticeRepository.save(Notice.create(1L, "예약 공지", "내용", NoticeDisplayType.banner,
                LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(7)));

        mockMvc.perform(get("/api/notices/" + scheduled.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/notices").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == " + scheduled.getId() + ")]").isEmpty());
    }

    @Test
    void adminCanSeeScheduledNotice() throws Exception {
        User admin = saveUser(UserRole.admin);
        Notice scheduled = noticeRepository.save(Notice.create(admin.getId(), "예약 공지", "내용",
                NoticeDisplayType.banner, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(7)));

        mockMvc.perform(get("/api/notices/" + scheduled.getId()).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void noticeAuthorIsAuthenticatedAdmin() throws Exception {
        User admin = saveUser(UserRole.admin);

        mockMvc.perform(post("/api/notices")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"createdBy\":999999,\"title\":\"점검\",\"content\":\"내용\",\"displayType\":\"banner\","
                                + "\"startAt\":\"2026-11-01T00:00:00\",\"endAt\":\"2026-11-08T00:00:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value(admin.getId()));
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
