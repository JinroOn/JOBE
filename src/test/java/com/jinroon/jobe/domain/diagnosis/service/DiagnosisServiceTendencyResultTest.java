package com.jinroon.jobe.domain.diagnosis.service;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.consultation.repository.ConsultationLogRepository;
import com.jinroon.jobe.domain.consultation.repository.ConsultationSessionRepository;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.diagnosis.entity.TendencyEvalResult;
import com.jinroon.jobe.domain.diagnosis.repository.CompetencyEvalResultRepository;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisEssayAnswerRepository;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisExamAnswerRepository;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisSessionRepository;
import com.jinroon.jobe.domain.diagnosis.repository.ExamQuestionRepository;
import com.jinroon.jobe.domain.diagnosis.repository.TendencyEvalResultRepository;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanItemRepository;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanRepository;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanRiskNoteRepository;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.domain.result.repository.ResultMajorScoreRepository;
import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DiagnosisServiceTendencyResultTest {

    @Mock
    private DiagnosisSessionRepository diagnosisSessionRepository;

    @Mock
    private ExamQuestionRepository examQuestionRepository;

    @Mock
    private DiagnosisExamAnswerRepository examAnswerRepository;

    @Mock
    private DiagnosisEssayAnswerRepository essayAnswerRepository;

    @Mock
    private CompetencyEvalResultRepository competencyEvalResultRepository;

    @Mock
    private TendencyEvalResultRepository tendencyEvalResultRepository;

    @Mock
    private DiagnosisResultRepository diagnosisResultRepository;

    @Mock
    private ResultMajorScoreRepository resultMajorScoreRepository;

    @Mock
    private MajorWeeklyPlanRepository majorWeeklyPlanRepository;

    @Mock
    private MajorWeeklyPlanItemRepository majorWeeklyPlanItemRepository;

    @Mock
    private MajorWeeklyPlanRiskNoteRepository majorWeeklyPlanRiskNoteRepository;

    @Mock
    private ConsultationSessionRepository consultationSessionRepository;

    @Mock
    private ConsultationLogRepository consultationLogRepository;

    private DiagnosisService diagnosisService;

    @BeforeEach
    void setUp() {
        diagnosisService = new DiagnosisService(
                diagnosisSessionRepository,
                examQuestionRepository,
                examAnswerRepository,
                essayAnswerRepository,
                competencyEvalResultRepository,
                tendencyEvalResultRepository,
                diagnosisResultRepository,
                resultMajorScoreRepository,
                majorWeeklyPlanRepository,
                majorWeeklyPlanItemRepository,
                majorWeeklyPlanRiskNoteRepository,
                consultationSessionRepository,
                consultationLogRepository,
                new TendencyScoringService(new ObjectMapper()),
                org.mockito.Mockito.mock(com.jinroon.jobe.domain.user.service.UserWriteGuard.class)
        );
    }

    @Test
    void createsTendencyResultFromSessionAnswersIgnoringPostedScores() {
        DiagnosisSession session = session(10L, 7L, allAnswers("A"));

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(tendencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.empty());
        when(tendencyEvalResultRepository.save(any(TendencyEvalResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // 요청 본문은 전부 80점이지만 서버는 A/B 응답으로 다시 계산한다.
        TendencyEvalResult result = diagnosisService.createTendencyResultForUser(tendencyValues(10L, 80.0f), 7L);

        assertThat(result.getDiagnosisSessionId()).isEqualTo(10L);
        assertThat(result.getLogicalInquiry()).isEqualTo(100.0f);
        assertThat(result.getTheoryAcademic()).isEqualTo(100.0f);
        assertThat(result.getSystemOperation()).isEqualTo(67.0f);
        assertThat(result.getLifeHealth()).isEqualTo(50.0f);
        assertThat(result.getPracticalTech()).isEqualTo(33.0f);
        assertThat(result.getEducationGuide()).isEqualTo(0.0f);
        verify(tendencyEvalResultRepository).save(any(TendencyEvalResult.class));
    }

    @Test
    void updatesExistingTendencyResultForSameSession() {
        DiagnosisSession session = session(10L, 7L, allAnswers("B"));
        TendencyEvalResult existing = tendencyResult(55L, 10L, 20.0f);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(tendencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(existing));

        TendencyEvalResult result = diagnosisService.createTendencyResultForUser(tendencyValues(10L, 90.0f), 7L);

        assertThat(result).isSameAs(existing);
        assertThat(result.getEducationGuide()).isEqualTo(100.0f);
        assertThat(result.getLogicalInquiry()).isEqualTo(0.0f);
        verify(tendencyEvalResultRepository, never()).save(any());
    }

    @Test
    void rejectsOtherUsersSession() {
        DiagnosisSession session = session(10L, 7L, allAnswers("A"));
        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> diagnosisService.createTendencyResultForUser(tendencyValues(10L, 80.0f), 99L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DIAGNOSIS_SESSION_NOT_FOUND);

        verify(tendencyEvalResultRepository, never()).findByDiagnosisSessionId(any());
        verify(tendencyEvalResultRepository, never()).save(any());
    }

    @Test
    void rejectsIncompleteAnswers() {
        String elevenAnswers = allAnswers("A").replace(",\"11\":\"A\"", "");
        DiagnosisSession session = session(10L, 7L, elevenAnswers);
        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> diagnosisService.createTendencyResultForUser(tendencyValues(10L, 80.0f), 7L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DIAGNOSIS_TENDENCY_ANSWERS_INCOMPLETE);
        verify(tendencyEvalResultRepository, never()).save(any());
    }

    @Test
    void rejectsSessionWithoutAnswers() {
        DiagnosisSession session = session(10L, 7L, null);
        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> diagnosisService.createTendencyResultForUser(tendencyValues(10L, 80.0f), 7L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DIAGNOSIS_TENDENCY_ANSWERS_INCOMPLETE);
    }

    @Test
    void repeatedSaveKeepsSingleRepositoryInsert() {
        DiagnosisSession session = session(10L, 7L, allAnswers("A"));
        TendencyEvalResult existing = tendencyResult(55L, 10L, 40.0f);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(tendencyEvalResultRepository.findByDiagnosisSessionId(10L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(tendencyEvalResultRepository.save(any(TendencyEvalResult.class))).thenAnswer(invocation -> {
            TendencyEvalResult saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 55L);
            return saved;
        });

        diagnosisService.createTendencyResultForUser(tendencyValues(10L, 30.0f), 7L);
        TendencyEvalResult second = diagnosisService.createTendencyResultForUser(tendencyValues(10L, 70.0f), 7L);

        assertThat(second).isSameAs(existing);
        assertThat(second.getLogicalInquiry()).isEqualTo(100.0f);
        verify(tendencyEvalResultRepository).save(any(TendencyEvalResult.class));
    }

    private String allAnswers(String choice) {
        StringBuilder json = new StringBuilder("{\"tendencyAnswers\":{");
        for (int i = 0; i < TendencyScoringService.QUESTION_COUNT; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(i).append("\":\"").append(choice).append('"');
        }
        return json.append("}}").toString();
    }

    private DiagnosisSession session(Long id, Long userId, String inputSnapshot) {
        DiagnosisSession session = EntityFormMapper.create(
                DiagnosisSession.class,
                Map.of(
                        "userId", userId,
                        "status", "in_progress",
                        "currentStep", 2,
                        "startedAt", "2026-06-03T00:00:00"
                )
        );
        ReflectionTestUtils.setField(session, "inputSnapshot", inputSnapshot);
        ReflectionTestUtils.setField(session, "id", id);
        return session;
    }

    private TendencyEvalResult tendencyResult(Long id, Long sessionId, Float score) {
        TendencyEvalResult result = EntityFormMapper.create(TendencyEvalResult.class, tendencyValues(sessionId, score));
        ReflectionTestUtils.setField(result, "id", id);
        return result;
    }

    private Map<String, Object> tendencyValues(Long sessionId, Float score) {
        return new HashMap<>(Map.ofEntries(
                entry("diagnosisSessionId", sessionId),
                entry("logicalInquiry", score),
                entry("practicalTech", score),
                entry("artCreative", score),
                entry("socialCooperation", score),
                entry("lifeHealth", score),
                entry("educationGuide", score),
                entry("theoryAcademic", score),
                entry("dataAnalytics", score),
                entry("systemOperation", score)
        ));
    }
}
