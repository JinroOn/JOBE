package com.jinroon.jobe.domain.diagnosis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static java.util.Map.entry;

import com.jinroon.jobe.domain.diagnosis.entity.CompetencyEvalResult;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisExamAnswer;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.diagnosis.entity.ExamQuestion;
import com.jinroon.jobe.domain.diagnosis.enums.DiagnosisEnums.CompetencyCategory;
import com.jinroon.jobe.domain.diagnosis.repository.CompetencyEvalResultRepository;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisExamAnswerRepository;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisSessionRepository;
import com.jinroon.jobe.domain.diagnosis.repository.ExamQuestionRepository;
import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DiagnosisScoringServiceTest {

    @Mock
    private DiagnosisSessionRepository diagnosisSessionRepository;

    @Mock
    private DiagnosisExamAnswerRepository examAnswerRepository;

    @Mock
    private ExamQuestionRepository examQuestionRepository;

    @Mock
    private CompetencyEvalResultRepository competencyEvalResultRepository;

    private DiagnosisScoringService scoringService;

    @BeforeEach
    void setUp() {
        scoringService = new DiagnosisScoringService(
                diagnosisSessionRepository,
                examAnswerRepository,
                examQuestionRepository,
                competencyEvalResultRepository
        );
    }

    @Test
    void scoresCompetencyFromSavedAnswersUsingServerCorrectAnswer() {
        DiagnosisSession session = session(10L, 7L);
        DiagnosisExamAnswer answer1 = answer(1L, 10L, 101L, "c", false);
        DiagnosisExamAnswer answer2 = answer(2L, 10L, 102L, "B", true);
        ExamQuestion question1 = question(101L, "C", 1.0f, 1.0f, 0.0f);
        ExamQuestion question2 = question(102L, "A", 1.0f, 0.0f, 1.0f);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examAnswerRepository.findByDiagnosisSessionId(10L)).thenReturn(List.of(answer1, answer2));
        when(examQuestionRepository.findAllById(List.of(101L, 102L))).thenReturn(List.of(question1, question2));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.empty());
        when(competencyEvalResultRepository.save(any(CompetencyEvalResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CompetencyEvalResult result = scoringService.scoreCompetencyForUser(10L, 7L);

        assertThat(answer1.getCorrect()).isTrue();
        assertThat(answer2.getCorrect()).isFalse();
        assertThat(result.getDiagnosisSessionId()).isEqualTo(10L);
        assertThat(result.getMathLogic()).isEqualTo(50.0f);
        assertThat(result.getProblemSolving()).isEqualTo(100.0f);
        assertThat(result.getInfoTech()).isEqualTo(0.0f);
        assertThat(result.getImplementation()).isEqualTo(0.0f);
        assertThat(result.getSystemUnderstanding()).isEqualTo(0.0f);
        assertThat(result.getDataAnalysis()).isEqualTo(0.0f);
        assertThat(result.getCommunication()).isEqualTo(0.0f);
        assertThat(result.getCollaboration()).isEqualTo(0.0f);
        assertThat(result.getSelfManagement()).isEqualTo(0.0f);
    }

    @Test
    void rejectsOtherUsersSession() {
        DiagnosisSession session = session(10L, 7L);
        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> scoringService.scoreCompetencyForUser(10L, 99L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(examAnswerRepository, never()).findByDiagnosisSessionId(any());
    }

    @Test
    void throwsWhenAnswersAreMissing() {
        DiagnosisSession session = session(10L, 7L);
        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examAnswerRepository.findByDiagnosisSessionId(10L)).thenReturn(List.of());

        assertThatThrownBy(() -> scoringService.scoreCompetencyForUser(10L, 7L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DIAGNOSIS_ANSWER_NOT_FOUND);

        verify(examQuestionRepository, never()).findAllById(any());
    }

    @Test
    void throwsWhenAnsweredQuestionIsMissing() {
        DiagnosisSession session = session(10L, 7L);
        DiagnosisExamAnswer answer = answer(1L, 10L, 101L, "A", false);
        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examAnswerRepository.findByDiagnosisSessionId(10L)).thenReturn(List.of(answer));
        when(examQuestionRepository.findAllById(List.of(101L))).thenReturn(List.of());

        assertThatThrownBy(() -> scoringService.scoreCompetencyForUser(10L, 7L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DIAGNOSIS_QUESTION_NOT_FOUND);

        verify(competencyEvalResultRepository, never()).save(any());
    }

    @Test
    void updatesExistingCompetencyResultInsteadOfCreatingDuplicate() {
        DiagnosisSession session = session(10L, 7L);
        DiagnosisExamAnswer answer = answer(1L, 10L, 101L, "A", false);
        ExamQuestion question = question(101L, "A", 1.0f, 0.0f, 0.0f);
        CompetencyEvalResult existing = competencyResult(55L, 10L);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examAnswerRepository.findByDiagnosisSessionId(10L)).thenReturn(List.of(answer));
        when(examQuestionRepository.findAllById(List.of(101L))).thenReturn(List.of(question));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(existing));

        CompetencyEvalResult result = scoringService.scoreCompetencyForUser(10L, 7L);

        assertThat(result).isSameAs(existing);
        assertThat(result.getMathLogic()).isEqualTo(100.0f);
        assertThat(result.getProblemSolving()).isEqualTo(0.0f);
        verify(competencyEvalResultRepository, never()).save(any());
    }

    @Test
    void zeroMaxWeightAxisIsScoredAsZero() {
        DiagnosisSession session = session(10L, 7L);
        DiagnosisExamAnswer answer = answer(1L, 10L, 101L, "A", false);
        ExamQuestion question = question(101L, "A", 0.0f, 0.0f, 0.0f);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examAnswerRepository.findByDiagnosisSessionId(10L)).thenReturn(List.of(answer));
        when(examQuestionRepository.findAllById(List.of(101L))).thenReturn(List.of(question));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.empty());
        when(competencyEvalResultRepository.save(any(CompetencyEvalResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CompetencyEvalResult result = scoringService.scoreCompetencyForUser(10L, 7L);

        assertThat(result.getMathLogic()).isEqualTo(0.0f);
        assertThat(result.getProblemSolving()).isEqualTo(0.0f);
        assertThat(result.getDataAnalysis()).isEqualTo(0.0f);
    }

    @Test
    void appliesDifficultyWeightWhenScoringCompetency() {
        DiagnosisSession session = session(10L, 7L);
        DiagnosisExamAnswer answer1 = answer(1L, 10L, 101L, "A", false);
        DiagnosisExamAnswer answer2 = answer(2L, 10L, 102L, "B", false);
        ExamQuestion easyQuestion = question(101L, "A", 1.0f, 0.0f, 0.0f, 1);
        ExamQuestion hardQuestion = question(102L, "A", 1.0f, 0.0f, 0.0f, 5);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examAnswerRepository.findByDiagnosisSessionId(10L)).thenReturn(List.of(answer1, answer2));
        when(examQuestionRepository.findAllById(List.of(101L, 102L))).thenReturn(List.of(easyQuestion, hardQuestion));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.empty());
        when(competencyEvalResultRepository.save(any(CompetencyEvalResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CompetencyEvalResult result = scoringService.scoreCompetencyForUser(10L, 7L);

        assertThat(result.getMathLogic()).isEqualTo(30.0f);
    }

    @Test
    void scoresSkippedAnswerAsIncorrectAndIncludesQuestionWeight() {
        DiagnosisSession session = session(10L, 7L);
        DiagnosisExamAnswer answered = answer(1L, 10L, 101L, "A", true);
        DiagnosisExamAnswer skipped = answer(2L, 10L, 102L, null, true);
        ExamQuestion question1 = question(101L, "A", 1.0f, 0.0f, 0.0f);
        ExamQuestion question2 = question(102L, "B", 1.0f, 0.0f, 0.0f);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examAnswerRepository.findByDiagnosisSessionId(10L)).thenReturn(List.of(answered, skipped));
        when(examQuestionRepository.findAllById(List.of(101L, 102L))).thenReturn(List.of(question1, question2));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.empty());
        when(competencyEvalResultRepository.save(any(CompetencyEvalResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CompetencyEvalResult result = scoringService.scoreCompetencyForUser(10L, 7L);

        assertThat(answered.getCorrect()).isTrue();
        assertThat(skipped.getCorrect()).isFalse();
        assertThat(result.getMathLogic()).isEqualTo(50.0f);
    }

    @Test
    void fastCorrectAnswerReceivesSmallSpeedBonus() {
        DiagnosisSession session = session(10L, 7L);
        DiagnosisExamAnswer fastCorrect = answer(1L, 10L, 101L, "A", false, 10);
        DiagnosisExamAnswer wrong = answer(2L, 10L, 102L, "B", false, 10);
        ExamQuestion question1 = question(101L, "A", 1.0f, 0.0f, 0.0f, null, 60);
        ExamQuestion question2 = question(102L, "A", 1.0f, 0.0f, 0.0f, null, 60);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examAnswerRepository.findByDiagnosisSessionId(10L)).thenReturn(List.of(fastCorrect, wrong));
        when(examQuestionRepository.findAllById(List.of(101L, 102L))).thenReturn(List.of(question1, question2));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.empty());
        when(competencyEvalResultRepository.save(any(CompetencyEvalResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CompetencyEvalResult result = scoringService.scoreCompetencyForUser(10L, 7L);

        assertThat(result.getMathLogic()).isEqualTo(52.0f);
    }

    @Test
    void suspiciouslyInstantCorrectAnswerReceivesNoSpeedBonus() {
        DiagnosisSession session = session(10L, 7L);
        DiagnosisExamAnswer instantCorrect = answer(1L, 10L, 101L, "A", false, 1);
        DiagnosisExamAnswer wrong = answer(2L, 10L, 102L, "B", false, 10);
        ExamQuestion question1 = question(101L, "A", 1.0f, 0.0f, 0.0f, null, 60);
        ExamQuestion question2 = question(102L, "A", 1.0f, 0.0f, 0.0f, null, 60);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examAnswerRepository.findByDiagnosisSessionId(10L)).thenReturn(List.of(instantCorrect, wrong));
        when(examQuestionRepository.findAllById(List.of(101L, 102L))).thenReturn(List.of(question1, question2));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.empty());
        when(competencyEvalResultRepository.save(any(CompetencyEvalResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CompetencyEvalResult result = scoringService.scoreCompetencyForUser(10L, 7L);

        assertThat(result.getMathLogic()).isEqualTo(50.0f);
    }

    @Test
    void slowCorrectAnswerReceivesNoSpeedBonus() {
        DiagnosisSession session = session(10L, 7L);
        DiagnosisExamAnswer slowCorrect = answer(1L, 10L, 101L, "A", false, 45);
        DiagnosisExamAnswer wrong = answer(2L, 10L, 102L, "B", false, 10);
        ExamQuestion question1 = question(101L, "A", 1.0f, 0.0f, 0.0f, null, 60);
        ExamQuestion question2 = question(102L, "A", 1.0f, 0.0f, 0.0f, null, 60);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examAnswerRepository.findByDiagnosisSessionId(10L)).thenReturn(List.of(slowCorrect, wrong));
        when(examQuestionRepository.findAllById(List.of(101L, 102L))).thenReturn(List.of(question1, question2));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.empty());
        when(competencyEvalResultRepository.save(any(CompetencyEvalResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CompetencyEvalResult result = scoringService.scoreCompetencyForUser(10L, 7L);

        assertThat(result.getMathLogic()).isEqualTo(50.0f);
    }

    @Test
    void fastWrongAnswerReceivesNoSpeedBonus() {
        DiagnosisSession session = session(10L, 7L);
        DiagnosisExamAnswer wrong = answer(1L, 10L, 101L, "B", true, 10);
        ExamQuestion question = question(101L, "A", 1.0f, 0.0f, 0.0f, null, 60);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examAnswerRepository.findByDiagnosisSessionId(10L)).thenReturn(List.of(wrong));
        when(examQuestionRepository.findAllById(List.of(101L))).thenReturn(List.of(question));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.empty());
        when(competencyEvalResultRepository.save(any(CompetencyEvalResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CompetencyEvalResult result = scoringService.scoreCompetencyForUser(10L, 7L);

        assertThat(wrong.getCorrect()).isFalse();
        assertThat(result.getMathLogic()).isEqualTo(0.0f);
    }

    @Test
    void fastSkippedAnswerReceivesNoSpeedBonus() {
        DiagnosisSession session = session(10L, 7L);
        DiagnosisExamAnswer skipped = answer(1L, 10L, 101L, null, true, 10);
        ExamQuestion question = question(101L, "A", 1.0f, 0.0f, 0.0f, null, 60);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examAnswerRepository.findByDiagnosisSessionId(10L)).thenReturn(List.of(skipped));
        when(examQuestionRepository.findAllById(List.of(101L))).thenReturn(List.of(question));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.empty());
        when(competencyEvalResultRepository.save(any(CompetencyEvalResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CompetencyEvalResult result = scoringService.scoreCompetencyForUser(10L, 7L);

        assertThat(skipped.getCorrect()).isFalse();
        assertThat(result.getMathLogic()).isEqualTo(0.0f);
    }

    @Test
    void fastCorrectAnswerDoesNotPushAxisScoreAboveOneHundred() {
        DiagnosisSession session = session(10L, 7L);
        DiagnosisExamAnswer fastCorrect = answer(1L, 10L, 101L, "A", false, 10);
        ExamQuestion question = question(101L, "A", 1.0f, 0.0f, 0.0f, null, 60);

        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(examAnswerRepository.findByDiagnosisSessionId(10L)).thenReturn(List.of(fastCorrect));
        when(examQuestionRepository.findAllById(List.of(101L))).thenReturn(List.of(question));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.empty());
        when(competencyEvalResultRepository.save(any(CompetencyEvalResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CompetencyEvalResult result = scoringService.scoreCompetencyForUser(10L, 7L);

        assertThat(result.getMathLogic()).isEqualTo(100.0f);
    }

    private DiagnosisSession session(Long id, Long userId) {
        DiagnosisSession session = EntityFormMapper.create(
                DiagnosisSession.class,
                Map.of(
                        "userId", userId,
                        "status", "in_progress",
                        "currentStep", 1,
                        "startedAt", "2026-06-03T00:00:00"
                )
        );
        ReflectionTestUtils.setField(session, "id", id);
        return session;
    }

    private DiagnosisExamAnswer answer(Long id, Long sessionId, Long questionId, String selectedAnswer, Boolean correct) {
        return answer(id, sessionId, questionId, selectedAnswer, correct, null);
    }

    private DiagnosisExamAnswer answer(
            Long id,
            Long sessionId,
            Long questionId,
            String selectedAnswer,
            Boolean correct,
            Integer responseSec
    ) {
        Map<String, Object> values = new HashMap<>();
        values.put("diagnosisSessionId", sessionId);
        values.put("examQuestionId", questionId);
        values.put("selectedAnswer", selectedAnswer);
        values.put("correct", correct);
        if (responseSec != null) {
            values.put("responseSec", responseSec);
        }
        DiagnosisExamAnswer answer = EntityFormMapper.create(
                DiagnosisExamAnswer.class,
                values
        );
        ReflectionTestUtils.setField(answer, "id", id);
        return answer;
    }

    private ExamQuestion question(Long id, String correctAnswer, Float mathLogic, Float problemSolving, Float dataAnalysis) {
        return question(id, correctAnswer, mathLogic, problemSolving, dataAnalysis, null);
    }

    private ExamQuestion question(
            Long id,
            String correctAnswer,
            Float mathLogic,
            Float problemSolving,
            Float dataAnalysis,
            Integer difficulty
    ) {
        return question(id, correctAnswer, mathLogic, problemSolving, dataAnalysis, difficulty, 60);
    }

    private ExamQuestion question(
            Long id,
            String correctAnswer,
            Float mathLogic,
            Float problemSolving,
            Float dataAnalysis,
            Integer difficulty,
            Integer timeLimitSec
    ) {
        Map<String, Object> values = new HashMap<>();
        values.put("competencyCategory", CompetencyCategory.math_logic);
        values.put("questionText", "question");
        values.put("optionA", "A");
        values.put("optionB", "B");
        values.put("optionC", "C");
        values.put("optionD", "D");
        values.put("correctAnswer", correctAnswer);
        values.put("timeLimitSec", timeLimitSec);
        values.put("difficulty", difficulty);
        values.put("wMathLogic", mathLogic);
        values.put("wProblemSolving", problemSolving);
        values.put("wInfoTech", 0.0f);
        values.put("wImplementation", 0.0f);
        values.put("wSystemUnderstanding", 0.0f);
        values.put("wDataAnalysis", dataAnalysis);
        values.put("wCommunication", 0.0f);
        values.put("wCollaboration", 0.0f);
        values.put("wSelfManagement", 0.0f);

        ExamQuestion question = EntityFormMapper.create(
                ExamQuestion.class,
                values
        );
        ReflectionTestUtils.setField(question, "id", id);
        return question;
    }

    private CompetencyEvalResult competencyResult(Long id, Long sessionId) {
        CompetencyEvalResult result = EntityFormMapper.create(
                CompetencyEvalResult.class,
                Map.ofEntries(
                        entry("diagnosisSessionId", sessionId),
                        entry("mathLogic", 1.0f),
                        entry("problemSolving", 2.0f),
                        entry("infoTech", 3.0f),
                        entry("implementation", 4.0f),
                        entry("systemUnderstanding", 5.0f),
                        entry("dataAnalysis", 6.0f),
                        entry("communication", 7.0f),
                        entry("collaboration", 8.0f),
                        entry("selfManagement", 9.0f)
                )
        );
        ReflectionTestUtils.setField(result, "id", id);
        return result;
    }
}
