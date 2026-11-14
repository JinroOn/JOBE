package com.jinroon.jobe.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.diagnosis.enums.DiagnosisEnums.DiagnosisStatus;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisSessionRepository;
import com.jinroon.jobe.domain.diagnosis.repository.ExamQuestionRepository;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.enums.UserEnums.UserRole;
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
 * 3.2 기준선: 진단·결과·계획 API의 인증·소유권 규칙을 HTTP 수준에서 고정한다.
 * 서비스 단위 테스트는 이미 있으므로 여기서는 컨트롤러·보안 설정을 거친 응답 코드만 본다.
 * 로컬 MySQL(docker compose up -d mysql)이 필요하다. 테스트 데이터는 트랜잭션 롤백으로 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DiagnosisResultAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DiagnosisSessionRepository diagnosisSessionRepository;

    @Autowired
    private DiagnosisResultRepository diagnosisResultRepository;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private ExamQuestionRepository examQuestionRepository;

    // ---- 진단 ----

    @Test
    void questionsRequireLogin() throws Exception {
        mockMvc.perform(get("/api/diagnoses/questions")).andExpect(status().isUnauthorized());
    }

    @Test
    void questionsAreAvailableToMember() throws Exception {
        User member = saveUser(UserRole.member);

        mockMvc.perform(get("/api/diagnoses/questions").header("Authorization", bearer(member)))
                .andExpect(status().isOk());
    }

    // ---- 3.2 2단계: 정답 숨김 (N02) ----

    @Test
    void questionsDoNotExposeAnswerOrScoringWeights() throws Exception {
        User member = saveUser(UserRole.member);
        Long questionId = examQuestionRepository.findAll().get(0).getId();

        mockMvc.perform(get("/api/diagnoses/questions").header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].questionText").exists())
                .andExpect(jsonPath("$[0].optionA").exists())
                .andExpect(jsonPath("$[0].correctAnswer").doesNotExist())
                .andExpect(jsonPath("$[0].wMathLogic").doesNotExist())
                .andExpect(jsonPath("$[0].wmathLogic").doesNotExist());
        mockMvc.perform(get("/api/diagnoses/questions/" + questionId).header("Authorization", bearer(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correctAnswer").doesNotExist());
    }

    @Test
    void examAnswersDoNotExposeCorrectness() throws Exception {
        User owner = saveUser(UserRole.member);
        DiagnosisSession session = saveSession(owner);
        Long questionId = examQuestionRepository.findAll().stream()
                .filter(question -> !question.isEssay())
                .findFirst().orElseThrow().getId();

        mockMvc.perform(post("/api/diagnoses/exam-answers")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diagnosisSessionId\":" + session.getId() + ",\"examQuestionId\":" + questionId
                                + ",\"selectedAnswer\":\"A\",\"responseSec\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.selectedAnswer").value("A"))
                .andExpect(jsonPath("$.correct").doesNotExist());
        mockMvc.perform(post("/api/diagnoses/sessions/" + session.getId() + "/score")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/diagnoses/sessions/" + session.getId() + "/exam-answers")
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].correct").doesNotExist());
    }

    // ---- 3.2 3단계: 사용자가 점수·결과를 직접 쓰는 경로 차단 (N04·N05) ----

    @Test
    void removedScoreAndResultWriteEndpointsAreRejected() throws Exception {
        User owner = saveUser(UserRole.member);
        DiagnosisResult result = saveResult(owner);
        String token = bearer(owner);
        String json = "{\"diagnosisSessionId\":" + result.getDiagnosisSessionId()
                + ",\"diagnosisResultId\":" + result.getId() + ",\"majorId\":1,\"finalScore\":100,\"mathLogic\":100}";

        mockMvc.perform(post("/api/diagnoses/competency-results").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/results").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(patch("/api/results/" + result.getId()).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"topMajorsJson\":\"[999]\"}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/results/major-scores").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is4xxClientError());

        DiagnosisResult reloaded = diagnosisResultRepository.findById(result.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getTopMajorsJson()).isEqualTo("[]");
    }

    @Test
    void createSessionIgnoresClientOwnerAndStartsInProgress() throws Exception {
        User owner = saveUser(UserRole.member);
        User other = saveUser(UserRole.member);

        mockMvc.perform(post("/api/diagnoses/sessions").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + other.getId() + ",\"currentStep\":1,\"inputSnapshot\":\"{}\","
                                + "\"completedAt\":\"2026-01-01T00:00:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(owner.getId()))
                .andExpect(jsonPath("$.status").value("in_progress"))
                .andExpect(jsonPath("$.completedAt").doesNotExist());
    }

    @Test
    void createSessionRejectsCompletedStatus() throws Exception {
        User owner = saveUser(UserRole.member);

        mockMvc.perform(post("/api/diagnoses/sessions").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"completed\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateSessionCannotMarkCompleted() throws Exception {
        User owner = saveUser(UserRole.member);
        DiagnosisSession session = saveSession(owner);

        mockMvc.perform(patch("/api/diagnoses/sessions/" + session.getId()).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"completed\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateSessionAllowsProgressFields() throws Exception {
        User owner = saveUser(UserRole.member);
        DiagnosisSession session = saveSession(owner);

        mockMvc.perform(patch("/api/diagnoses/sessions/" + session.getId()).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"in_progress\",\"currentStep\":3,\"inputSnapshot\":\"{\\\"a\\\":1}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value(3))
                .andExpect(jsonPath("$.status").value("in_progress"));
    }

    @Test
    void completedSessionCannotBeModified() throws Exception {
        User owner = saveUser(UserRole.member);
        DiagnosisSession session = saveSession(owner);
        session.complete(LocalDateTime.now());
        diagnosisSessionRepository.saveAndFlush(session);

        mockMvc.perform(patch("/api/diagnoses/sessions/" + session.getId()).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"inputSnapshot\":\"{}\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void ownSessionIsReadable() throws Exception {
        User owner = saveUser(UserRole.member);
        DiagnosisSession session = saveSession(owner);

        mockMvc.perform(get("/api/diagnoses/sessions/" + session.getId()).header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
    }

    @Test
    void otherUsersSessionIsForbidden() throws Exception {
        User owner = saveUser(UserRole.member);
        User other = saveUser(UserRole.member);
        DiagnosisSession session = saveSession(owner);

        mockMvc.perform(get("/api/diagnoses/sessions/" + session.getId()).header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    void otherUsersSessionCannotBeScored() throws Exception {
        User owner = saveUser(UserRole.member);
        User other = saveUser(UserRole.member);
        DiagnosisSession session = saveSession(owner);

        mockMvc.perform(post("/api/diagnoses/sessions/" + session.getId() + "/score")
                        .header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());
    }

    // ---- 결과 ----

    @Test
    void otherUsersResultIsForbidden() throws Exception {
        User owner = saveUser(UserRole.member);
        User other = saveUser(UserRole.member);
        DiagnosisResult result = saveResult(owner);

        mockMvc.perform(get("/api/results/" + result.getId()).header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/results/" + result.getId() + "/major-scores").header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());
    }

    @Test
    void sharedResultIsPublicWithOwnerNickname() throws Exception {
        User owner = saveUser(UserRole.member);
        DiagnosisResult result = saveResult(owner);

        mockMvc.perform(get("/api/results/share/" + result.getShareToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerNickname").value(owner.getNickname()));
        mockMvc.perform(get("/api/results/share/" + result.getShareToken() + "/major-scores"))
                .andExpect(status().isOk());
    }

    // ---- 계획 ----

    @Test
    void planForOtherUsersResultIsForbidden() throws Exception {
        User owner = saveUser(UserRole.member);
        User other = saveUser(UserRole.member);
        DiagnosisResult result = saveResult(owner);

        mockMvc.perform(post("/api/plans")
                        .header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diagnosisResultId\":" + result.getId() + ",\"resultMajorScoreId\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void plansOfOtherUsersResultAreForbidden() throws Exception {
        User owner = saveUser(UserRole.member);
        User other = saveUser(UserRole.member);
        DiagnosisResult result = saveResult(owner);

        mockMvc.perform(get("/api/plans/results/" + result.getId()).header("Authorization", bearer(other)))
                .andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private User saveUser(UserRole role) {
        String email = "diag-access-" + UUID.randomUUID() + "@example.com";
        User user = User.registerEmailUser(email, "pbkdf2$1$c2FsdA==$aGFzaA==", "테스트", null);
        ReflectionTestUtils.setField(user, "role", role);
        return userRepository.save(user);
    }

    private DiagnosisSession saveSession(User owner) {
        DiagnosisSession session = newEntity(DiagnosisSession.class);
        ReflectionTestUtils.setField(session, "userId", owner.getId());
        ReflectionTestUtils.setField(session, "status", DiagnosisStatus.in_progress);
        ReflectionTestUtils.setField(session, "currentStep", 1);
        ReflectionTestUtils.setField(session, "startedAt", LocalDateTime.now());
        return diagnosisSessionRepository.save(session);
    }

    private DiagnosisResult saveResult(User owner) {
        DiagnosisSession session = saveSession(owner);
        return diagnosisResultRepository.save(DiagnosisResult.create(
                session.getId(),
                owner.getId(),
                "{}",
                "{}",
                "[]",
                UUID.randomUUID().toString().replace("-", "")
        ));
    }

    private String bearer(User user) {
        return "Bearer " + jwtProvider.createToken(user);
    }

    private <T> T newEntity(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
