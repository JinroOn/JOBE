package com.jinroon.jobe.domain.diagnosis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jinroon.jobe.domain.consultation.repository.ConsultationLogRepository;
import com.jinroon.jobe.domain.consultation.repository.ConsultationSessionRepository;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisExamAnswer;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.diagnosis.entity.ExamQuestion;
import com.jinroon.jobe.domain.diagnosis.enums.DiagnosisEnums.CompetencyCategory;
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
class DiagnosisServiceExamAnswerTest {

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
    void createsNewExamAnswerForUser() {
        DiagnosisSession session = session(10L, 7L);
        ExamQuestion question = question(101L);
        Map<String, Object> values = answerValues(10L, 101L, "a", 12, true);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examQuestionRepository.findById(101L)).thenReturn(Optional.of(question));
        when(examAnswerRepository.findByDiagnosisSessionIdAndExamQuestionId(10L, 101L))
                .thenReturn(Optional.empty());
        when(examAnswerRepository.save(any(DiagnosisExamAnswer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DiagnosisExamAnswer result = diagnosisService.createExamAnswerForUser(values, 7L);

        assertThat(result.getDiagnosisSessionId()).isEqualTo(10L);
        assertThat(result.getExamQuestionId()).isEqualTo(101L);
        assertThat(result.getSelectedAnswer()).isEqualTo("A");
        assertThat(result.getResponseSec()).isEqualTo(12);
        assertThat(result.getCorrect()).isFalse();
        verify(examAnswerRepository).save(any(DiagnosisExamAnswer.class));
    }

    @Test
    void updatesExistingExamAnswerForSameSessionAndQuestion() {
        DiagnosisSession session = session(10L, 7L);
        ExamQuestion question = question(101L);
        DiagnosisExamAnswer existing = answer(55L, 10L, 101L, "A", 10, true);
        Map<String, Object> values = answerValues(10L, 101L, "d", 25, true);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examQuestionRepository.findById(101L)).thenReturn(Optional.of(question));
        when(examAnswerRepository.findByDiagnosisSessionIdAndExamQuestionId(10L, 101L))
                .thenReturn(Optional.of(existing));

        DiagnosisExamAnswer result = diagnosisService.createExamAnswerForUser(values, 7L);

        assertThat(result).isSameAs(existing);
        assertThat(result.getSelectedAnswer()).isEqualTo("D");
        assertThat(result.getResponseSec()).isEqualTo(25);
        assertThat(result.getCorrect()).isFalse();
        verify(examAnswerRepository, never()).save(any());
    }

    @Test
    void repeatedSaveKeepsSingleRepositoryInsert() {
        DiagnosisSession session = session(10L, 7L);
        ExamQuestion question = question(101L);
        DiagnosisExamAnswer existing = answer(55L, 10L, 101L, "A", 10, false);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examQuestionRepository.findById(101L)).thenReturn(Optional.of(question));
        when(examAnswerRepository.findByDiagnosisSessionIdAndExamQuestionId(10L, 101L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(examAnswerRepository.save(any(DiagnosisExamAnswer.class))).thenAnswer(invocation -> {
            DiagnosisExamAnswer saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 55L);
            return saved;
        });

        diagnosisService.createExamAnswerForUser(answerValues(10L, 101L, "A", 10, true), 7L);
        DiagnosisExamAnswer second = diagnosisService.createExamAnswerForUser(answerValues(10L, 101L, "B", 15, false), 7L);

        assertThat(second).isSameAs(existing);
        assertThat(second.getSelectedAnswer()).isEqualTo("B");
        verify(examAnswerRepository).save(any(DiagnosisExamAnswer.class));
    }

    @Test
    void rejectsOtherUsersSession() {
        DiagnosisSession session = session(10L, 7L);
        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> diagnosisService.createExamAnswerForUser(answerValues(10L, 101L, "A", 10, true), 99L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(examQuestionRepository, never()).findById(any());
        verify(examAnswerRepository, never()).findByDiagnosisSessionIdAndExamQuestionId(any(), any());
        verify(examAnswerRepository, never()).save(any());
    }

    @Test
    void savesNullSelectedAnswerForSkippedQuestion() {
        DiagnosisSession session = session(10L, 7L);
        ExamQuestion question = question(101L);
        Map<String, Object> values = answerValues(10L, 101L, null, 30, true);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examQuestionRepository.findById(101L)).thenReturn(Optional.of(question));
        when(examAnswerRepository.findByDiagnosisSessionIdAndExamQuestionId(10L, 101L))
                .thenReturn(Optional.empty());
        when(examAnswerRepository.save(any(DiagnosisExamAnswer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DiagnosisExamAnswer result = diagnosisService.createExamAnswerForUser(values, 7L);

        assertThat(result.getSelectedAnswer()).isNull();
        assertThat(result.getCorrect()).isFalse();
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

    private ExamQuestion question(Long id) {
        ExamQuestion question = EntityFormMapper.create(
                ExamQuestion.class,
                Map.of(
                        "competencyCategory", CompetencyCategory.math_logic,
                        "questionText", "question",
                        "optionA", "A",
                        "optionB", "B",
                        "optionC", "C",
                        "optionD", "D",
                        "correctAnswer", "A",
                        "timeLimitSec", 60,
                        "difficulty", 3
                )
        );
        ReflectionTestUtils.setField(question, "id", id);
        return question;
    }

    private DiagnosisExamAnswer answer(
            Long id,
            Long sessionId,
            Long questionId,
            String selectedAnswer,
            Integer responseSec,
            Boolean correct
    ) {
        DiagnosisExamAnswer answer = EntityFormMapper.create(
                DiagnosisExamAnswer.class,
                answerValues(sessionId, questionId, selectedAnswer, responseSec, correct)
        );
        ReflectionTestUtils.setField(answer, "id", id);
        return answer;
    }

    private Map<String, Object> answerValues(
            Long sessionId,
            Long questionId,
            String selectedAnswer,
            Integer responseSec,
            Boolean correct
    ) {
        Map<String, Object> values = new HashMap<>();
        values.put("diagnosisSessionId", sessionId);
        values.put("examQuestionId", questionId);
        values.put("selectedAnswer", selectedAnswer);
        values.put("responseSec", responseSec);
        values.put("correct", correct);
        return values;
    }
}
