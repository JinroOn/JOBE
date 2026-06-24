package com.jinroon.jobe.domain.diagnosis.service;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                consultationLogRepository
        );
    }

    @Test
    void createsNewTendencyResultForUser() {
        DiagnosisSession session = session(10L, 7L);
        Map<String, Object> values = tendencyValues(10L, 80.0f);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(tendencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.empty());
        when(tendencyEvalResultRepository.save(any(TendencyEvalResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TendencyEvalResult result = diagnosisService.createTendencyResultForUser(values, 7L);

        assertThat(result.getDiagnosisSessionId()).isEqualTo(10L);
        assertThat(result.getLogicalInquiry()).isEqualTo(80.0f);
        assertThat(result.getSystemOperation()).isEqualTo(80.0f);
        verify(tendencyEvalResultRepository).save(any(TendencyEvalResult.class));
    }

    @Test
    void updatesExistingTendencyResultForSameSession() {
        DiagnosisSession session = session(10L, 7L);
        TendencyEvalResult existing = tendencyResult(55L, 10L, 20.0f);
        Map<String, Object> values = tendencyValues(10L, 90.0f);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(tendencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(existing));

        TendencyEvalResult result = diagnosisService.createTendencyResultForUser(values, 7L);

        assertThat(result).isSameAs(existing);
        assertThat(result.getLogicalInquiry()).isEqualTo(90.0f);
        assertThat(result.getPracticalTech()).isEqualTo(90.0f);
        verify(tendencyEvalResultRepository, never()).save(any());
    }

    @Test
    void rejectsOtherUsersSession() {
        DiagnosisSession session = session(10L, 7L);
        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> diagnosisService.createTendencyResultForUser(tendencyValues(10L, 80.0f), 99L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(tendencyEvalResultRepository, never()).findByDiagnosisSessionId(any());
        verify(tendencyEvalResultRepository, never()).save(any());
    }

    @Test
    void repeatedSaveKeepsSingleRepositoryInsert() {
        DiagnosisSession session = session(10L, 7L);
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
        assertThat(second.getLogicalInquiry()).isEqualTo(70.0f);
        verify(tendencyEvalResultRepository).save(any(TendencyEvalResult.class));
    }

    @Test
    void tendencyScoresAreClampedToValidRangeOnUpdate() {
        DiagnosisSession session = session(10L, 7L);
        TendencyEvalResult existing = tendencyResult(55L, 10L, 40.0f);
        Map<String, Object> values = tendencyValues(10L, 50.0f);
        values.put("logicalInquiry", -10.0f);
        values.put("practicalTech", 150.0f);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(tendencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(existing));

        TendencyEvalResult result = diagnosisService.createTendencyResultForUser(values, 7L);

        assertThat(result.getLogicalInquiry()).isEqualTo(0.0f);
        assertThat(result.getPracticalTech()).isEqualTo(100.0f);
    }

    private DiagnosisSession session(Long id, Long userId) {
        DiagnosisSession session = EntityFormMapper.create(
                DiagnosisSession.class,
                Map.of(
                        "userId", userId,
                        "status", "in_progress",
                        "currentStep", 2,
                        "startedAt", "2026-06-03T00:00:00"
                )
        );
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
