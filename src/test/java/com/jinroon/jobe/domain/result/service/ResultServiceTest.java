package com.jinroon.jobe.domain.result.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.diagnosis.entity.CompetencyEvalResult;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.diagnosis.entity.TendencyEvalResult;
import com.jinroon.jobe.domain.diagnosis.dto.DiagnosisProfileAdjustment;
import com.jinroon.jobe.domain.diagnosis.dto.DiagnosisProfileSnapshot;
import com.jinroon.jobe.domain.diagnosis.repository.CompetencyEvalResultRepository;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisSessionRepository;
import com.jinroon.jobe.domain.diagnosis.repository.TendencyEvalResultRepository;
import com.jinroon.jobe.domain.diagnosis.service.DiagnosisProfileScoringService;
import com.jinroon.jobe.domain.diagnosis.enums.DiagnosisEnums.DiagnosisStatus;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
import com.jinroon.jobe.domain.major.service.MajorDatasetContextService;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlan;
import com.jinroon.jobe.domain.plan.service.PlanService;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.entity.ResultMajorScore;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.domain.result.repository.ResultMajorScoreRepository;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.global.client.AiServiceClient;
import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest;
import com.jinroon.jobe.global.client.dto.response.RecommendationCommentResponse;
import com.jinroon.jobe.global.common.ai.AiGenerationStatus;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ResultServiceTest {

    @Mock
    private DiagnosisResultRepository diagnosisResultRepository;

    @Mock
    private ResultMajorScoreRepository resultMajorScoreRepository;

    @Mock
    private DiagnosisSessionRepository diagnosisSessionRepository;

    @Mock
    private CompetencyEvalResultRepository competencyEvalResultRepository;

    @Mock
    private TendencyEvalResultRepository tendencyEvalResultRepository;

    @Mock
    private MajorRepository majorRepository;

    @Mock
    private DiagnosisProfileScoringService diagnosisProfileScoringService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AiServiceClient aiServiceClient;

    @Mock
    private MajorDatasetContextService majorDatasetContextService;

    @Mock
    private PlanService planService;

    private ResultService resultService;

    @BeforeEach
    void setUp() {
        resultService = new ResultService(
                diagnosisResultRepository,
                resultMajorScoreRepository,
                diagnosisSessionRepository,
                competencyEvalResultRepository,
                tendencyEvalResultRepository,
                majorRepository,
                diagnosisProfileScoringService,
                userRepository,
                aiServiceClient,
                majorDatasetContextService,
                new ObjectMapper(),
                planService
        );
    }

    @Test
    void createMajorScoreForUserAppliesProfileAdjustment() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        DiagnosisSession session = diagnosisSession(10L, 7L, "{\"selectedSubjects\":[\"정보/코딩\"]}");
        Major major = major(100L, "컴퓨터공학과");
        Map<String, Object> values = new HashMap<>();
        values.put("diagnosisResultId", 1L);
        values.put("majorId", 100L);
        values.put("competencyScore", 80.0f);
        values.put("tendencyScore", 70.0f);
        values.put("finalScore", 76.0f);
        values.put("rank", 1);
        values.put("failed", false);

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(majorRepository.findById(100L)).thenReturn(Optional.of(major));
        when(diagnosisProfileScoringService.calculateProfileAdjustment(session, major))
                .thenReturn(new DiagnosisProfileAdjustment(4.0f, List.of("프로필 보정 사유")));
        when(diagnosisProfileScoringService.adjustedFinalScore(80.0f, 70.0f, 76.0f, 4.0f))
                .thenReturn(80.0f);
        when(resultMajorScoreRepository.save(any(ResultMajorScore.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(1L)).thenReturn(List.of());

        ResultMajorScore actual = resultService.createMajorScoreForUser(values, 7L);

        assertThat(actual.getFinalScore()).isEqualTo(80.0f);
        assertThat(actual.getRecommendationReason()).isEqualTo("프로필 보정 사유");
        assertThat(values.get("finalScore")).isEqualTo(80.0f);
    }

    @Test
    void createMajorScoreForUserReranksScoresAfterAdjustment() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        DiagnosisSession session = diagnosisSession(10L, 7L, "{\"selectedSubjects\":[\"정보/코딩\"]}");
        Major major = major(100L, "컴퓨터공학과");
        ResultMajorScore lowerScore = resultMajorScore(11L, 1L, 101L, 1, 83.0f);
        ResultMajorScore failedHighScore = resultMajorScore(12L, 1L, 102L, 2, 99.0f);
        ReflectionTestUtils.setField(failedHighScore, "failed", true);
        Map<String, Object> values = new HashMap<>();
        values.put("diagnosisResultId", 1L);
        values.put("majorId", 100L);
        values.put("finalScore", 86.0f);
        values.put("rank", 3);
        values.put("failed", false);
        ResultMajorScore[] savedScore = new ResultMajorScore[1];

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(majorRepository.findById(100L)).thenReturn(Optional.of(major));
        when(diagnosisProfileScoringService.calculateProfileAdjustment(session, major))
                .thenReturn(DiagnosisProfileAdjustment.neutral());
        when(diagnosisProfileScoringService.adjustedFinalScore(null, null, 86.0f, 0.0f))
                .thenReturn(86.0f);
        when(resultMajorScoreRepository.save(any(ResultMajorScore.class))).thenAnswer(invocation -> {
            ResultMajorScore saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 13L);
            savedScore[0] = saved;
            return saved;
        });
        when(resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(1L))
                .thenAnswer(invocation -> List.of(lowerScore, failedHighScore, savedScore[0]));

        ResultMajorScore actual = resultService.createMajorScoreForUser(values, 7L);

        assertThat(actual.getRank()).isEqualTo(1);
        assertThat(lowerScore.getRank()).isEqualTo(2);
        assertThat(failedHighScore.getRank()).isEqualTo(3);
    }

    @Test
    void completeDiagnosisResultForUserCreatesResultAndMajorScores() {
        DiagnosisSession session = diagnosisSession(10L, 7L, "{}");
        CompetencyEvalResult competency = competencyResult(10L);
        TendencyEvalResult tendency = tendencyResult(10L);
        Major firstMajor = scoringMajor(100L, "Software", 50.0f, 50.0f);
        Major secondMajor = scoringMajor(101L, "Design", 60.0f, 60.0f);

        stubCompleteDependencies(session, competency, tendency, List.of(firstMajor, secondMajor));

        DiagnosisResult result = resultService.completeDiagnosisResultForUser(10L, 7L);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getDiagnosisSessionId()).isEqualTo(10L);
        assertThat(result.getUserId()).isEqualTo(7L);
        assertThat(result.getShareToken()).isNotBlank();
        assertThat(result.getCompetencyVector()).contains("mathLogic");
        assertThat(result.getTendencyVector()).contains("tendLogicalInquiry");
        assertThat(session.getStatus()).isEqualTo(DiagnosisStatus.completed);
        assertThat(session.getCompletedAt()).isNotNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ResultMajorScore>> scoresCaptor = ArgumentCaptor.forClass(List.class);
        verify(resultMajorScoreRepository).deleteAllByDiagnosisResultId(1L);
        verify(resultMajorScoreRepository).saveAll(scoresCaptor.capture());
        assertThat(scoresCaptor.getValue()).hasSize(2);
        assertThat(scoresCaptor.getValue()).extracting(ResultMajorScore::getRank).containsExactly(1, 2);
    }

    @Test
    void completeDiagnosisResultForUserReusesExistingResultAndReplacesScores() {
        DiagnosisSession session = diagnosisSession(10L, 7L, "{}");
        CompetencyEvalResult competency = competencyResult(10L);
        TendencyEvalResult tendency = tendencyResult(10L);
        DiagnosisResult existing = diagnosisResult(5L, 10L, 7L);
        ReflectionTestUtils.setField(existing, "shareToken", "existing-share-token");
        existing.applyAiComment("stale ai comment", "communicationScore");
        existing.markAiCommentSucceeded();
        Major major = scoringMajor(100L, "Software", 50.0f, 50.0f);

        stubCompleteDependencies(session, competency, tendency, List.of(major));
        when(diagnosisResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(existing));

        DiagnosisResult result = resultService.completeDiagnosisResultForUser(10L, 7L);

        assertThat(result).isSameAs(existing);
        assertThat(result.getShareToken()).isEqualTo("existing-share-token");
        assertThat(result.getAiComment()).isNull();
        assertThat(result.getWeaknessFocus()).isNull();
        assertThat(result.getAiCommentStatus()).isEqualTo(AiGenerationStatus.NOT_REQUESTED);
        assertThat(result.getAiCommentErrorMessage()).isNull();
        assertThat(result.getAiCommentRequestedAt()).isNull();
        assertThat(result.getAiCommentCompletedAt()).isNull();
        verify(resultMajorScoreRepository).deleteAllByDiagnosisResultId(5L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ResultMajorScore>> scoresCaptor = ArgumentCaptor.forClass(List.class);
        verify(resultMajorScoreRepository).saveAll(scoresCaptor.capture());
        assertThat(scoresCaptor.getValue()).hasSize(1);
        assertThat(scoresCaptor.getValue().get(0).getDiagnosisResultId()).isEqualTo(5L);
    }

    @Test
    void completeDiagnosisResultForUserFailsWhenCompetencyResultIsMissing() {
        DiagnosisSession session = diagnosisSession(10L, 7L, "{}");
        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resultService.completeDiagnosisResultForUser(10L, 7L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DIAGNOSIS_COMPETENCY_RESULT_NOT_FOUND);
    }

    @Test
    void completeDiagnosisResultForUserFailsWhenTendencyResultIsMissing() {
        DiagnosisSession session = diagnosisSession(10L, 7L, "{}");
        CompetencyEvalResult competency = competencyResult(10L);
        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(tendencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resultService.completeDiagnosisResultForUser(10L, 7L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DIAGNOSIS_TENDENCY_RESULT_NOT_FOUND);
    }

    @Test
    void completeDiagnosisResultForUserRejectsOtherUsersSession() {
        DiagnosisSession session = diagnosisSession(10L, 7L, "{}");
        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> resultService.completeDiagnosisResultForUser(10L, 99L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(competencyEvalResultRepository, never()).findByDiagnosisSessionId(any());
    }

    @Test
    void completeDiagnosisResultForUserAppliesProfileAdjustmentToFinalScore() {
        DiagnosisSession session = diagnosisSession(10L, 7L, "{\"dreamJob\":\"software developer\"}");
        CompetencyEvalResult competency = competencyResult(10L);
        TendencyEvalResult tendency = tendencyResult(10L);
        Major major = scoringMajor(100L, "Software", 80.0f, 80.0f);

        stubCompleteDependencies(session, competency, tendency, List.of(major));
        when(diagnosisProfileScoringService.calculateProfileAdjustment(session, major))
                .thenReturn(new DiagnosisProfileAdjustment(4.0f, List.of("profile fit")));

        resultService.completeDiagnosisResultForUser(10L, 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ResultMajorScore>> scoresCaptor = ArgumentCaptor.forClass(List.class);
        verify(resultMajorScoreRepository).saveAll(scoresCaptor.capture());
        ResultMajorScore score = scoresCaptor.getValue().get(0);

        assertThat(score.getFinalScore()).isGreaterThan(score.getCompetencyScore() * 0.6f + score.getTendencyScore() * 0.4f);
        assertThat(score.getRecommendationReason()).isEqualTo("profile fit");
    }

    @Test
    void completeDiagnosisResultForUserRanksFailedMajorsAfterNonFailedMajorsAndUsesStableTieBreakers() {
        DiagnosisSession session = diagnosisSession(10L, 7L, "{}");
        CompetencyEvalResult competency = competencyResult(10L);
        TendencyEvalResult tendency = tendencyResult(10L);
        Major failedHighScore = scoringMajor(100L, "Failed", 50.0f, 50.0f);
        ReflectionTestUtils.setField(failedHighScore, "thrMathLogic", 95.0f);
        Major tieSecondById = scoringMajor(102L, "Tie B", 70.0f, 70.0f);
        Major tieFirstById = scoringMajor(101L, "Tie A", 70.0f, 70.0f);

        stubCompleteDependencies(session, competency, tendency, List.of(failedHighScore, tieSecondById, tieFirstById));

        resultService.completeDiagnosisResultForUser(10L, 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ResultMajorScore>> scoresCaptor = ArgumentCaptor.forClass(List.class);
        verify(resultMajorScoreRepository).saveAll(scoresCaptor.capture());
        List<ResultMajorScore> scores = scoresCaptor.getValue();

        assertThat(scores).extracting(ResultMajorScore::getMajorId).containsExactly(101L, 102L, 100L);
        assertThat(scores).extracting(ResultMajorScore::getRank).containsExactly(1, 2, 3);
        assertThat(scores.get(2).getFailed()).isTrue();
    }

    @Test
    void completeDiagnosisResultForUserCreatesInitialPlanForTopNonFailedMajor() {
        DiagnosisSession session = diagnosisSession(10L, 7L, "{}");
        CompetencyEvalResult competency = competencyResult(10L);
        TendencyEvalResult tendency = tendencyResult(10L);
        Major major = scoringMajor(100L, "Software", 50.0f, 50.0f);

        stubCompleteDependencies(session, competency, tendency, List.of(major));

        resultService.completeDiagnosisResultForUser(10L, 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> planCaptor = ArgumentCaptor.forClass(Map.class);
        verify(planService).createPlan(planCaptor.capture());
        assertThat(planCaptor.getValue()).containsEntry("diagnosisResultId", 1L);
        assertThat(planCaptor.getValue()).containsEntry("resultMajorScoreId", 10L);
        assertThat(planCaptor.getValue()).containsEntry("activeVersion", true);
    }

    @Test
    void completeDiagnosisResultForUserDoesNotCreateDuplicatePlanWhenActivePlanExists() {
        DiagnosisSession session = diagnosisSession(10L, 7L, "{}");
        CompetencyEvalResult competency = competencyResult(10L);
        TendencyEvalResult tendency = tendencyResult(10L);
        Major major = scoringMajor(100L, "Software", 50.0f, 50.0f);
        MajorWeeklyPlan activePlan = activePlan(77L, 1L);

        stubCompleteDependencies(session, competency, tendency, List.of(major));
        when(planService.findPlansByResult(1L)).thenReturn(List.of(activePlan));

        resultService.completeDiagnosisResultForUser(10L, 7L);

        verify(planService, times(1)).findPlansByResult(1L);
        verify(planService, never()).createPlan(any());
    }

    @Test
    void generateAiCommentForUserStoresResultAndMajorComments() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore score = resultMajorScore(11L, 1L, 100L, 1, 87.5f);
        Major major = major(100L, "컴퓨터공학과");
        RecommendationCommentResponse response = new RecommendationCommentResponse(
                "AI 요약",
                List.of(new RecommendationCommentResponse.MajorComment(
                        "컴퓨터공학과",
                        1,
                        87.5,
                        "구현력",
                        "의사소통",
                        "구현력이 강해 잘 맞습니다."
                )),
                List.of("communicationScore"),
                "rec-comment-v1.2.0",
                "request-1"
        );

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(1L)).thenReturn(List.of(score));
        when(majorRepository.findAllById(List.of(100L))).thenReturn(List.of(major));
        when(aiServiceClient.getRecommendationComment(any(RecommendationCommentRequest.class))).thenReturn(response);

        DiagnosisResult actual = resultService.generateAiCommentForUser(1L, 7L);

        assertThat(actual).isSameAs(result);
        assertThat(result.getAiComment()).isEqualTo("AI 요약");
        assertThat(result.getWeaknessFocus()).isEqualTo("communicationScore");
        assertThat(score.getStrengths()).isEqualTo("구현력");
        assertThat(score.getWeaknesses()).isEqualTo("의사소통");
        assertThat(score.getRecommendationReason()).isEqualTo("구현력이 강해 잘 맞습니다.");
        assertThat(result.getAiCommentStatus()).isEqualTo(AiGenerationStatus.SUCCEEDED);
        assertThat(result.getAiCommentErrorMessage()).isNull();
        assertThat(result.getAiCommentRequestedAt()).isNotNull();
        assertThat(result.getAiCommentCompletedAt()).isNotNull();
    }

    @Test
    void generateAiCommentForUserRejectsOtherUsersResult() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));

        assertThatThrownBy(() -> resultService.generateAiCommentForUser(1L, 99L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(aiServiceClient, never()).getRecommendationComment(any());
    }

    @Test
    void generateAiCommentDoesNotCallAiWhenMajorScoresAreMissing() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        CompetencyEvalResult competency = competencyResult(10L);
        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> resultService.generateAiCommentForUser(1L, 7L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RESULT_MAJOR_SCORE_NOT_FOUND);

        assertThat(result.getAiCommentStatus()).isEqualTo(AiGenerationStatus.NOT_REQUESTED);
        verify(aiServiceClient, never()).getRecommendationComment(any());
    }

    @Test
    void generateAiCommentReturnsResultWhenAiServiceReturnsNull() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore score = resultMajorScore(11L, 1L, 100L, 1, 87.5f);
        Major major = major(100L, "컴퓨터공학과");

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(1L)).thenReturn(List.of(score));
        when(majorRepository.findAllById(List.of(100L))).thenReturn(List.of(major));
        when(aiServiceClient.getRecommendationComment(any(RecommendationCommentRequest.class))).thenReturn(null);

        DiagnosisResult actual = resultService.generateAiCommentForUser(1L, 7L);

        assertThat(actual).isSameAs(result);
        assertThat(result.getAiComment()).isNull();
        assertThat(score.getRecommendationReason()).isNull();
        assertThat(result.getAiCommentStatus()).isEqualTo(AiGenerationStatus.FAILED);
        assertThat(result.getAiCommentErrorMessage()).isEqualTo("ai-service recommendation response is null");
        assertThat(result.getAiCommentRequestedAt()).isNotNull();
        assertThat(result.getAiCommentCompletedAt()).isNotNull();
    }

    @Test
    void generateAiCommentMarksFailedWhenMajorCommentsAreEmpty() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore score = resultMajorScore(11L, 1L, 100L, 1, 87.5f);
        Major major = major(100L, "컴퓨터공학과");
        RecommendationCommentResponse response = new RecommendationCommentResponse(
                "AI 요약",
                List.of(),
                List.of("communicationScore"),
                "rec-comment-v1.2.0",
                "request-empty"
        );

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(1L)).thenReturn(List.of(score));
        when(majorRepository.findAllById(List.of(100L))).thenReturn(List.of(major));
        when(aiServiceClient.getRecommendationComment(any(RecommendationCommentRequest.class))).thenReturn(response);

        DiagnosisResult actual = resultService.generateAiCommentForUser(1L, 7L);

        assertThat(actual).isSameAs(result);
        assertThat(result.getAiComment()).isEqualTo("AI 요약");
        assertThat(result.getAiCommentStatus()).isEqualTo(AiGenerationStatus.FAILED);
        assertThat(result.getAiCommentErrorMessage())
                .isEqualTo("ai-service recommendation response has no major comments");
        assertThat(score.getRecommendationReason()).isNull();
    }

    @Test
    void generateAiCommentStoresMatchedMajorCommentsAndSkipsUnmatchedComments() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore firstScore = resultMajorScore(11L, 1L, 100L, 1, 87.5f);
        ResultMajorScore secondScore = resultMajorScore(12L, 1L, 101L, 2, 82.0f);
        Major firstMajor = major(100L, "Major A");
        RecommendationCommentResponse response = new RecommendationCommentResponse(
                "summary",
                List.of(new RecommendationCommentResponse.MajorComment(
                        "Major A",
                        1,
                        87.5,
                        "strength-a",
                        "weakness-a",
                        "reason-a"
                )),
                List.of("communicationScore"),
                "rec-comment-v1.2.0",
                "request-partial"
        );

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(1L))
                .thenReturn(List.of(firstScore, secondScore));
        when(majorRepository.findAllById(List.of(100L))).thenReturn(List.of(firstMajor));
        when(aiServiceClient.getRecommendationComment(any(RecommendationCommentRequest.class))).thenReturn(response);

        DiagnosisResult actual = resultService.generateAiCommentForUser(1L, 7L);

        assertThat(actual).isSameAs(result);
        assertThat(result.getAiComment()).isEqualTo("summary");
        assertThat(result.getWeaknessFocus()).isEqualTo("communicationScore");
        assertThat(firstScore.getStrengths()).isEqualTo("strength-a");
        assertThat(firstScore.getWeaknesses()).isEqualTo("weakness-a");
        assertThat(firstScore.getRecommendationReason()).isEqualTo("reason-a");
        assertThat(secondScore.getStrengths()).isNull();
        assertThat(secondScore.getWeaknesses()).isNull();
        assertThat(secondScore.getRecommendationReason()).isNull();
        assertThat(result.getAiCommentStatus()).isEqualTo(AiGenerationStatus.SUCCEEDED);
    }

    @Test
    void generateAiCommentRequestContainsRankOneMajorWithQuizPrimaryContext() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore firstScore = resultMajorScore(11L, 1L, 100L, 1, 87.5f);
        ResultMajorScore secondScore = resultMajorScore(12L, 1L, 101L, 2, 82.0f);
        Major computerScience = major(100L, "컴퓨터공학과", "공학", 90.0f, 85.0f, 50.0f);
        RecommendationCommentResponse response = new RecommendationCommentResponse(
                "AI 요약",
                List.of(new RecommendationCommentResponse.MajorComment(
                        "컴퓨터공학과",
                        1,
                        87.5,
                        "구현력",
                        "의사소통",
                        "구현력이 강해 잘 맞습니다."
                )),
                List.of("communicationScore"),
                "rec-comment-v1.2.0",
                "request-1"
        );

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(1L))
                .thenReturn(List.of(firstScore, secondScore));
        when(majorRepository.findAllById(List.of(100L))).thenReturn(List.of(computerScience));
        when(diagnosisSessionRepository.findById(10L))
                .thenReturn(Optional.of(diagnosisSession(10L, 7L, "{\"dreamJob\":\"AI 데이터 사이언티스트\"}")));
        when(diagnosisProfileScoringService.parse(any()))
                .thenReturn(new DiagnosisProfileSnapshot(
                        "1학년",
                        "AI 데이터 사이언티스트",
                        List.of("정보/코딩"),
                        4.5,
                        "practice",
                        80,
                        Map.of(),
                        "데이터로 문제를 해결하고 싶다"
                ));
        when(majorDatasetContextService.toRecommendationMajorContext(computerScience))
                .thenReturn(new RecommendationCommentRequest.MajorContext(
                        "공학",
                        "컴퓨터공학과 설명",
                        "컴퓨터공학과 근거",
                        List.of("소프트웨어 개발자"),
                        List.of("컴퓨터공학과 RAG snippet")
                ));
        when(aiServiceClient.getRecommendationComment(any(RecommendationCommentRequest.class))).thenReturn(response);

        resultService.generateAiCommentForUser(1L, 7L);

        ArgumentCaptor<RecommendationCommentRequest> requestCaptor =
                ArgumentCaptor.forClass(RecommendationCommentRequest.class);
        verify(aiServiceClient).getRecommendationComment(requestCaptor.capture());
        RecommendationCommentRequest request = requestCaptor.getValue();

        assertThat(request.topMajors()).hasSize(1);
        assertThat(request.topMajors().get(0).majorName()).isEqualTo("컴퓨터공학과");
        assertThat(request.topMajors().get(0).rankingOrder()).isEqualTo(1);
        assertThat(request.topMajors().get(0).strengths())
                .contains("do not mention scoring mechanics")
                .contains("Explain concrete fit points")
                .doesNotContain("Quiz competency is the primary diagnostic signal")
                .doesNotContain("tendency and profile data are supporting signals only")
                .doesNotContain("softwareImplementationScore")
                .doesNotContain("mathLogicalScore")
                .doesNotContain("systemUnderstandingScore");
        assertThat(request.topMajors().get(0).strengths()).hasSizeLessThanOrEqualTo(500);
        assertThat(request.topMajors().get(0).weaknesses())
                .isNotBlank()
                .doesNotContain("softwareImplementationScore")
                .doesNotContain("mathLogicalScore")
                .doesNotContain("systemUnderstandingScore");
        assertThat(request.topMajors().get(0).weaknesses()).hasSizeLessThanOrEqualTo(500);
        assertThat(request.recommendationGroups()).isEmpty();
        assertThat(request.profileContext()).isNotNull();
        assertThat(request.profileContext().dreamJob()).isEqualTo("AI 데이터 사이언티스트");
        assertThat(request.userContext()).isNotNull();
        assertThat(request.userContext().careerField()).isEqualTo("AI 데이터 사이언티스트");
        assertThat(request.topMajors().get(0).majorContext()).isNotNull();
        assertThat(request.topMajors().get(0).majorContext().ragSnippets()).containsExactly("컴퓨터공학과 RAG snippet");
        assertThat(firstScore.getRecommendationReason()).isEqualTo("구현력이 강해 잘 맞습니다.");
        assertThat(secondScore.getRecommendationReason()).isNull();
    }

    @Test
    void generateAiCommentWithTenMajorScoresSendsOnlyRankOneMajor() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore rankOneScore = resultMajorScore(11L, 1L, 201L, 1, 91.0f);
        ResultMajorScore rankTwoScore = resultMajorScore(12L, 1L, 202L, 2, 88.0f);
        ResultMajorScore rankThreeScore = resultMajorScore(13L, 1L, 203L, 3, 87.0f);
        ResultMajorScore rankFourScore = resultMajorScore(14L, 1L, 204L, 4, 86.0f);
        ResultMajorScore rankFiveScore = resultMajorScore(15L, 1L, 205L, 5, 85.0f);
        ResultMajorScore rankSixScore = resultMajorScore(16L, 1L, 206L, 6, 84.0f);
        ResultMajorScore rankSevenScore = resultMajorScore(17L, 1L, 207L, 7, 83.0f);
        ResultMajorScore rankEightScore = resultMajorScore(18L, 1L, 208L, 8, 82.0f);
        ResultMajorScore rankNineScore = resultMajorScore(19L, 1L, 209L, 9, 81.0f);
        ResultMajorScore rankTenScore = resultMajorScore(20L, 1L, 210L, 10, 80.0f);
        Major topMajor = major(201L, "Primary Major");
        RecommendationCommentResponse response = new RecommendationCommentResponse(
                "primary summary",
                List.of(new RecommendationCommentResponse.MajorComment(
                        "Primary Major",
                        1,
                        91.0,
                        "primary strength",
                        "primary weakness",
                        "primary reason"
                )),
                List.of("communicationScore"),
                "rec-comment-v1.2.0",
                "request-primary"
        );

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(1L))
                .thenReturn(List.of(
                        rankTenScore,
                        rankThreeScore,
                        rankOneScore,
                        rankSevenScore,
                        rankTwoScore,
                        rankNineScore,
                        rankFourScore,
                        rankSixScore,
                        rankFiveScore,
                        rankEightScore
                ));
        when(majorRepository.findAllById(List.of(201L)))
                .thenReturn(List.of(topMajor));
        when(aiServiceClient.getRecommendationComment(any(RecommendationCommentRequest.class))).thenReturn(response);

        DiagnosisResult actual = resultService.generateAiCommentForUser(1L, 7L);

        ArgumentCaptor<RecommendationCommentRequest> requestCaptor =
                ArgumentCaptor.forClass(RecommendationCommentRequest.class);
        verify(aiServiceClient).getRecommendationComment(requestCaptor.capture());
        RecommendationCommentRequest request = requestCaptor.getValue();

        assertThat(actual).isSameAs(result);
        assertThat(request.topMajors()).hasSize(1);
        assertThat(request.topMajors().get(0).majorName()).isEqualTo("Primary Major");
        assertThat(request.topMajors().get(0).rankingOrder()).isEqualTo(1);
        assertThat(request.topMajors()).extracting(RecommendationCommentRequest.TopMajor::majorName)
                .containsExactly("Primary Major");
        assertThat(request.recommendationGroups()).isEmpty();
        assertThat(result.getAiComment()).isEqualTo("primary summary");
        assertThat(result.getAiCommentStatus()).isEqualTo(AiGenerationStatus.SUCCEEDED);
        assertThat(rankOneScore.getRecommendationReason()).isEqualTo("primary reason");
        assertThat(rankTwoScore.getRecommendationReason()).isNull();
    }

    @Test
    void generateAiCommentSendsRankOneMajorEvenWhenOtherNonFailedMajorsExist() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore firstScore = resultMajorScore(11L, 1L, 301L, 1, 91.0f);
        ResultMajorScore failedSecondScore = resultMajorScore(12L, 1L, 302L, 2, 90.0f);
        ResultMajorScore thirdScore = resultMajorScore(13L, 1L, 303L, 3, 89.0f);
        ResultMajorScore failedFourthScore = resultMajorScore(14L, 1L, 304L, 4, 88.0f);
        ReflectionTestUtils.setField(failedSecondScore, "failed", true);
        ReflectionTestUtils.setField(failedFourthScore, "failed", true);
        Major firstMajor = major(301L, "First Major");
        RecommendationCommentResponse response = new RecommendationCommentResponse(
                "summary",
                List.of(new RecommendationCommentResponse.MajorComment(
                        "First Major",
                        1,
                        91.0,
                        "strength",
                        "weakness",
                        "reason"
                )),
                List.of("communicationScore"),
                "rec-comment-v1.2.0",
                "request-failed-fill"
        );

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(1L))
                .thenReturn(List.of(firstScore, failedSecondScore, thirdScore, failedFourthScore));
        when(majorRepository.findAllById(List.of(301L)))
                .thenReturn(List.of(firstMajor));
        when(aiServiceClient.getRecommendationComment(any(RecommendationCommentRequest.class))).thenReturn(response);

        resultService.generateAiCommentForUser(1L, 7L);

        ArgumentCaptor<RecommendationCommentRequest> requestCaptor =
                ArgumentCaptor.forClass(RecommendationCommentRequest.class);
        verify(aiServiceClient).getRecommendationComment(requestCaptor.capture());
        RecommendationCommentRequest request = requestCaptor.getValue();

        assertThat(request.topMajors()).extracting(RecommendationCommentRequest.TopMajor::majorName)
                .containsExactly("First Major");
    }

    @Test
    void generateAiCommentBlocksDuplicateCallWhenStatusIsPending() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        result.markAiCommentPending();
        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));

        assertThatThrownBy(() -> resultService.generateAiCommentForUser(1L, 7L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AI_GENERATION_IN_PROGRESS);

        verify(competencyEvalResultRepository, never()).findByDiagnosisSessionId(any());
        verify(aiServiceClient, never()).getRecommendationComment(any());
    }

    @Test
    void generateAiCommentSkipsSucceededResultWithoutForce() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        result.applyAiComment("existing comment", "communicationScore");
        result.markAiCommentSucceeded();
        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));

        DiagnosisResult actual = resultService.generateAiCommentForUser(1L, 7L);

        assertThat(actual).isSameAs(result);
        assertThat(result.getAiComment()).isEqualTo("existing comment");
        assertThat(result.getAiCommentStatus()).isEqualTo(AiGenerationStatus.SUCCEEDED);
        verify(competencyEvalResultRepository, never()).findByDiagnosisSessionId(any());
        verify(aiServiceClient, never()).getRecommendationComment(any());
    }

    @Test
    void generateAiCommentRetriesFailedResult() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        result.markAiCommentFailed("previous failure");
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore score = resultMajorScore(11L, 1L, 100L, 1, 87.5f);
        Major major = major(100L, "Major A");
        RecommendationCommentResponse response = new RecommendationCommentResponse(
                "retried summary",
                List.of(new RecommendationCommentResponse.MajorComment(
                        "Major A",
                        1,
                        87.5,
                        "strength-a",
                        "weakness-a",
                        "reason-a"
                )),
                List.of("communicationScore"),
                "rec-comment-v1.2.0",
                "request-retry"
        );

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(1L)).thenReturn(List.of(score));
        when(majorRepository.findAllById(List.of(100L))).thenReturn(List.of(major));
        when(aiServiceClient.getRecommendationComment(any(RecommendationCommentRequest.class))).thenReturn(response);

        DiagnosisResult actual = resultService.generateAiCommentForUser(1L, 7L);

        assertThat(actual).isSameAs(result);
        assertThat(result.getAiComment()).isEqualTo("retried summary");
        assertThat(result.getAiCommentStatus()).isEqualTo(AiGenerationStatus.SUCCEEDED);
        assertThat(result.getAiCommentErrorMessage()).isNull();
        assertThat(score.getRecommendationReason()).isEqualTo("reason-a");
    }

    @Test
    void generateAiCommentForceRegeneratesSucceededResult() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        result.applyAiComment("existing comment", "communicationScore");
        result.markAiCommentSucceeded();
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore score = resultMajorScore(11L, 1L, 100L, 1, 87.5f);
        Major major = major(100L, "Major A");
        RecommendationCommentResponse response = new RecommendationCommentResponse(
                "forced summary",
                List.of(new RecommendationCommentResponse.MajorComment(
                        "Major A",
                        1,
                        87.5,
                        "strength-a",
                        "weakness-a",
                        "reason-a"
                )),
                List.of("dataAnalysisScore"),
                "rec-comment-v1.2.0",
                "request-force"
        );

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(1L)).thenReturn(List.of(score));
        when(majorRepository.findAllById(List.of(100L))).thenReturn(List.of(major));
        when(aiServiceClient.getRecommendationComment(any(RecommendationCommentRequest.class))).thenReturn(response);

        DiagnosisResult actual = resultService.generateAiCommentForUser(1L, 7L, true);

        assertThat(actual).isSameAs(result);
        assertThat(result.getAiComment()).isEqualTo("forced summary");
        assertThat(result.getWeaknessFocus()).isEqualTo("dataAnalysisScore");
        assertThat(result.getAiCommentStatus()).isEqualTo(AiGenerationStatus.SUCCEEDED);
        assertThat(score.getRecommendationReason()).isEqualTo("reason-a");
    }

    private void stubCompleteDependencies(
            DiagnosisSession session,
            CompetencyEvalResult competency,
            TendencyEvalResult tendency,
            List<Major> majors
    ) {
        AtomicLong resultIds = new AtomicLong(1L);
        AtomicLong scoreIds = new AtomicLong(10L);
        when(diagnosisSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(session.getId())).thenReturn(Optional.of(competency));
        when(tendencyEvalResultRepository.findByDiagnosisSessionId(session.getId())).thenReturn(Optional.of(tendency));
        when(majorRepository.findAll()).thenReturn(majors);
        when(diagnosisResultRepository.findByDiagnosisSessionId(session.getId())).thenReturn(Optional.empty());
        when(diagnosisResultRepository.save(any(DiagnosisResult.class))).thenAnswer(invocation -> {
            DiagnosisResult result = invocation.getArgument(0);
            if (result.getId() == null) {
                ReflectionTestUtils.setField(result, "id", resultIds.getAndIncrement());
            }
            return result;
        });
        when(resultMajorScoreRepository.saveAll(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<ResultMajorScore> scores = invocation.getArgument(0);
            scores.stream()
                    .filter(score -> score.getId() == null)
                    .forEach(score -> ReflectionTestUtils.setField(score, "id", scoreIds.getAndIncrement()));
            return scores;
        });
        when(diagnosisProfileScoringService.calculateProfileAdjustment(any(DiagnosisSession.class), any(Major.class)))
                .thenReturn(DiagnosisProfileAdjustment.neutral());
        when(diagnosisProfileScoringService.adjustedFinalScore(any(), any(), any(), anyFloat()))
                .thenAnswer(invocation -> {
                    Float competencyScore = invocation.getArgument(0);
                    Float tendencyScore = invocation.getArgument(1);
                    float profileBonus = invocation.getArgument(3);
                    return Math.max(0.0f, Math.min(100.0f,
                            Math.round((competencyScore * 0.6f + tendencyScore * 0.4f + profileBonus) * 10.0f) / 10.0f));
                });
    }

    private DiagnosisResult diagnosisResult(Long id, Long sessionId, Long userId) {
        DiagnosisResult result = newEntity(DiagnosisResult.class);
        ReflectionTestUtils.setField(result, "id", id);
        ReflectionTestUtils.setField(result, "diagnosisSessionId", sessionId);
        ReflectionTestUtils.setField(result, "userId", userId);
        ReflectionTestUtils.setField(result, "competencyVector", "{}");
        ReflectionTestUtils.setField(result, "tendencyVector", "{}");
        return result;
    }

    private DiagnosisSession diagnosisSession(Long id, Long userId, String inputSnapshot) {
        DiagnosisSession session = newEntity(DiagnosisSession.class);
        ReflectionTestUtils.setField(session, "id", id);
        ReflectionTestUtils.setField(session, "userId", userId);
        ReflectionTestUtils.setField(session, "inputSnapshot", inputSnapshot);
        return session;
    }

    private CompetencyEvalResult competencyResult(Long sessionId) {
        CompetencyEvalResult result = newEntity(CompetencyEvalResult.class);
        ReflectionTestUtils.setField(result, "diagnosisSessionId", sessionId);
        ReflectionTestUtils.setField(result, "mathLogic", 80.0f);
        ReflectionTestUtils.setField(result, "problemSolving", 75.0f);
        ReflectionTestUtils.setField(result, "infoTech", 70.0f);
        ReflectionTestUtils.setField(result, "implementation", 85.0f);
        ReflectionTestUtils.setField(result, "systemUnderstanding", 65.0f);
        ReflectionTestUtils.setField(result, "dataAnalysis", 78.0f);
        ReflectionTestUtils.setField(result, "communication", 60.0f);
        ReflectionTestUtils.setField(result, "collaboration", 62.0f);
        ReflectionTestUtils.setField(result, "selfManagement", 72.0f);
        return result;
    }

    private TendencyEvalResult tendencyResult(Long sessionId) {
        TendencyEvalResult result = newEntity(TendencyEvalResult.class);
        ReflectionTestUtils.setField(result, "diagnosisSessionId", sessionId);
        ReflectionTestUtils.setField(result, "logicalInquiry", 70.0f);
        ReflectionTestUtils.setField(result, "practicalTech", 75.0f);
        ReflectionTestUtils.setField(result, "artCreative", 65.0f);
        ReflectionTestUtils.setField(result, "socialCooperation", 60.0f);
        ReflectionTestUtils.setField(result, "lifeHealth", 55.0f);
        ReflectionTestUtils.setField(result, "educationGuide", 58.0f);
        ReflectionTestUtils.setField(result, "theoryAcademic", 68.0f);
        ReflectionTestUtils.setField(result, "dataAnalytics", 72.0f);
        ReflectionTestUtils.setField(result, "systemOperation", 74.0f);
        return result;
    }

    private ResultMajorScore resultMajorScore(Long id, Long resultId, Long majorId, Integer rank, Float finalScore) {
        ResultMajorScore score = newEntity(ResultMajorScore.class);
        ReflectionTestUtils.setField(score, "id", id);
        ReflectionTestUtils.setField(score, "diagnosisResultId", resultId);
        ReflectionTestUtils.setField(score, "majorId", majorId);
        ReflectionTestUtils.setField(score, "rank", rank);
        ReflectionTestUtils.setField(score, "finalScore", finalScore);
        ReflectionTestUtils.setField(score, "failed", false);
        return score;
    }

    private MajorWeeklyPlan activePlan(Long id, Long resultId) {
        MajorWeeklyPlan plan = newEntity(MajorWeeklyPlan.class);
        ReflectionTestUtils.setField(plan, "id", id);
        ReflectionTestUtils.setField(plan, "diagnosisResultId", resultId);
        ReflectionTestUtils.setField(plan, "activeVersion", true);
        return plan;
    }

    private Major major(Long id, String name) {
        Major major = newEntity(Major.class);
        ReflectionTestUtils.setField(major, "id", id);
        ReflectionTestUtils.setField(major, "name", name);
        return major;
    }

    private Major scoringMajor(Long id, String name, Float competencyRequirement, Float tendencyRequirement) {
        Major major = major(id, name);
        ReflectionTestUtils.setField(major, "reqMathLogic", competencyRequirement);
        ReflectionTestUtils.setField(major, "reqProblemSolving", competencyRequirement);
        ReflectionTestUtils.setField(major, "reqInfoTech", competencyRequirement);
        ReflectionTestUtils.setField(major, "reqImplementation", competencyRequirement);
        ReflectionTestUtils.setField(major, "reqSystemUnderstanding", competencyRequirement);
        ReflectionTestUtils.setField(major, "reqDataAnalysis", competencyRequirement);
        ReflectionTestUtils.setField(major, "reqCommunication", competencyRequirement);
        ReflectionTestUtils.setField(major, "reqCollaboration", competencyRequirement);
        ReflectionTestUtils.setField(major, "reqSelfManagement", competencyRequirement);
        ReflectionTestUtils.setField(major, "tendLogicalInquiry", tendencyRequirement);
        ReflectionTestUtils.setField(major, "tendPracticalTech", tendencyRequirement);
        ReflectionTestUtils.setField(major, "tendArtCreative", tendencyRequirement);
        ReflectionTestUtils.setField(major, "tendSocialCooperation", tendencyRequirement);
        ReflectionTestUtils.setField(major, "tendLifeHealth", tendencyRequirement);
        ReflectionTestUtils.setField(major, "tendEducationGuide", tendencyRequirement);
        ReflectionTestUtils.setField(major, "tendTheoryAcademic", tendencyRequirement);
        ReflectionTestUtils.setField(major, "tendDataAnalytics", tendencyRequirement);
        ReflectionTestUtils.setField(major, "tendSystemOperation", tendencyRequirement);
        return major;
    }

    private Major major(Long id, String name, String category, Float implementation, Float systemUnderstanding,
                        Float dataAnalysis) {
        Major major = major(id, name);
        ReflectionTestUtils.setField(major, "category", category);
        ReflectionTestUtils.setField(major, "reqImplementation", implementation);
        ReflectionTestUtils.setField(major, "reqSystemUnderstanding", systemUnderstanding);
        ReflectionTestUtils.setField(major, "reqDataAnalysis", dataAnalysis);
        return major;
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
