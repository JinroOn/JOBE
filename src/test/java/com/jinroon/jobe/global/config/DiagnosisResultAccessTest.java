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
import com.jinroon.jobe.domain.result.entity.ResultMajorScore;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.domain.result.repository.ResultMajorScoreRepository;
import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.enums.UserEnums.UserRole;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.global.security.JwtProvider;
import java.time.LocalDateTime;
import java.util.List;
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
 * 3.2: 진단·결과·계획 API의 인증·소유권 규칙(기준선)과 정답 숨김, 쓰기 경로 차단,
 * 성향 서버 계산, 계획 점수 소속 확인, 공유 응답 축소를 HTTP 수준에서 확인한다.
 * 서비스 단위 테스트는 이미 있으므로 여기서는 컨트롤러·보안 설정을 거친 응답 코드만 본다.
 * 로컬 MySQL(docker compose -f docker-compose.test.yml up -d)이 필요하다. 테스트 데이터는 트랜잭션 롤백으로 지운다.
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

    @Autowired
    private ResultMajorScoreRepository resultMajorScoreRepository;

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
    void retiredDiagnosisCannotStartNewSessions() throws Exception {
        User owner = saveUser(UserRole.member);
        mockMvc.perform(post("/api/diagnoses/sessions").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currentStep\":1}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("LEGACY_DIAGNOSIS_RETIRED"));
        org.assertj.core.api.Assertions.assertThat(diagnosisSessionRepository.findByUserId(owner.getId())).isEmpty();
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
                .andExpect(status().isNotFound());
    }

    @Test
    void otherUsersSessionCannotBeScored() throws Exception {
        User owner = saveUser(UserRole.member);
        User other = saveUser(UserRole.member);
        DiagnosisSession session = saveSession(owner);

        mockMvc.perform(post("/api/diagnoses/sessions/" + session.getId() + "/score")
                        .header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
    }

    // ---- 결과 ----

    @Test
    void otherUsersResultIsForbidden() throws Exception {
        User owner = saveUser(UserRole.member);
        User other = saveUser(UserRole.member);
        DiagnosisResult result = saveResult(owner);

        mockMvc.perform(get("/api/results/" + result.getId()).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/results/" + result.getId() + "/major-scores").header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
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

    // ---- 3.2 6단계: 공유 응답 축소 (문서 4.2) ----

    @Test
    void sharedResultExposesOnlyAllowlistedFields() throws Exception {
        User owner = saveUser(UserRole.member);
        DiagnosisResult result = saveResult(owner);
        resultMajorScoreRepository.save(ResultMajorScore.of(
                result.getId(), 1L, 70.0f, 80.0f, 76.0f, 1, false, "이유"));

        mockMvc.perform(get("/api/results/share/" + result.getShareToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerNickname").exists())
                .andExpect(jsonPath("$.competencyVector").exists())
                .andExpect(jsonPath("$.aiCommentStatus").exists())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.diagnosisSessionId").doesNotExist())
                .andExpect(jsonPath("$.shareToken").doesNotExist())
                .andExpect(jsonPath("$.tendencyVector").doesNotExist())
                .andExpect(jsonPath("$.aiCommentErrorMessage").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());
        mockMvc.perform(get("/api/results/share/" + result.getShareToken() + "/major-scores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].majorId").value(1))
                .andExpect(jsonPath("$[0].finalScore").value(76.0))
                .andExpect(jsonPath("$[0].recommendationReason").value("이유"))
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[0].diagnosisResultId").doesNotExist());
    }

    @Test
    void legacyMajorScoreEndpointsDisplayTiesWithoutChangingStoredRanks() throws Exception {
        User owner = saveUser(UserRole.member);
        DiagnosisResult result = saveResult(owner);
        long id = result.getId();
        resultMajorScoreRepository.saveAll(List.of(
                ResultMajorScore.of(id, 1L, 70.0f, 80.0f, 76.0f, 1, false, "첫 번째 전공"),
                ResultMajorScore.of(id, 2L, 72.0f, 80.0f, 76.0f, 2, false, "두 번째 전공"),
                ResultMajorScore.of(id, 3L, 73.0f, 78.0f, 76.0f, 3, false, "역량 점수가 다른 전공"),
                ResultMajorScore.of(id, 4L, 70.0f, 80.0f, 76.0f, 4, true, "기준 미충족 전공")
        ));

        mockMvc.perform(get("/api/results/" + id + "/major-scores").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[0].displayRank").value(1))
                .andExpect(jsonPath("$[0].tieCount").value(2))
                .andExpect(jsonPath("$[1].rank").value(2))
                .andExpect(jsonPath("$[1].displayRank").value(1))
                .andExpect(jsonPath("$[1].tieCount").value(2))
                .andExpect(jsonPath("$[1].diagnosisResultId").value(id))
                .andExpect(jsonPath("$[1].createdAt").exists())
                .andExpect(jsonPath("$[1].id").exists())
                .andExpect(jsonPath("$[2].displayRank").value(3))
                .andExpect(jsonPath("$[2].tieCount").value(1))
                .andExpect(jsonPath("$[3].displayRank").value(4))
                .andExpect(jsonPath("$[3].tieCount").value(1));

        mockMvc.perform(get("/api/results/share/" + result.getShareToken() + "/major-scores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tieCount").value(2))
                .andExpect(jsonPath("$[1].rank").value(2))
                .andExpect(jsonPath("$[1].displayRank").value(1))
                .andExpect(jsonPath("$[1].tieCount").value(2))
                .andExpect(jsonPath("$[1].diagnosisResultId").doesNotExist())
                .andExpect(jsonPath("$[1].id").doesNotExist())
                .andExpect(jsonPath("$[2].displayRank").value(3))
                .andExpect(jsonPath("$[3].displayRank").value(4));
    }

    // ---- 3.2 4단계: 성향 점수 서버 계산 (N03) ----

    @Test
    void tendencyResultIsComputedFromSessionAnswersNotRequestBody() throws Exception {
        User owner = saveUser(UserRole.member);
        DiagnosisSession session = saveSession(owner);
        StringBuilder answers = new StringBuilder("{\"tendencyAnswers\":{");
        for (int i = 0; i < 12; i++) {
            answers.append(i == 0 ? "" : ",").append('"').append(i).append("\":\"A\"");
        }
        ReflectionTestUtils.setField(session, "inputSnapshot", answers.append("}}").toString());
        diagnosisSessionRepository.saveAndFlush(session);

        mockMvc.perform(post("/api/diagnoses/tendency-results").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diagnosisSessionId\":" + session.getId() + ",\"logicalInquiry\":0,"
                                + "\"educationGuide\":100,\"systemOperation\":100}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.logicalInquiry").value(100.0))
                .andExpect(jsonPath("$.educationGuide").value(0.0))
                .andExpect(jsonPath("$.systemOperation").value(67.0));
    }

    @Test
    void tendencyResultRequiresAllAnswers() throws Exception {
        User owner = saveUser(UserRole.member);
        DiagnosisSession session = saveSession(owner);

        mockMvc.perform(post("/api/diagnoses/tendency-results").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diagnosisSessionId\":" + session.getId() + ",\"logicalInquiry\":100}"))
                .andExpect(status().isBadRequest());
    }

    // ---- 3.2 5단계: 계획의 전공 점수 소속 확인 (N06) ----



    // ---- 계획 ----



    @Test
    void plansOfOtherUsersResultAreForbidden() throws Exception {
        User owner = saveUser(UserRole.member);
        User other = saveUser(UserRole.member);
        DiagnosisResult result = saveResult(owner);

        mockMvc.perform(get("/api/plans/results/" + result.getId()).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
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
