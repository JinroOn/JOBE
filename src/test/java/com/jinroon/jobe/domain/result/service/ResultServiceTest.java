package com.jinroon.jobe.domain.result.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jinroon.jobe.domain.diagnosis.entity.CompetencyEvalResult;
import com.jinroon.jobe.domain.diagnosis.repository.CompetencyEvalResultRepository;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisSessionRepository;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
import com.jinroon.jobe.domain.major.service.MajorDatasetContextService;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.entity.ResultMajorScore;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.domain.result.repository.ResultMajorScoreRepository;
import com.jinroon.jobe.global.client.AiServiceClient;
import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest;
import com.jinroon.jobe.global.client.dto.response.RecommendationCommentResponse;
import com.jinroon.jobe.global.common.ai.AiGenerationStatus;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.util.List;
import java.util.Optional;
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
    private MajorRepository majorRepository;

    @Mock
    private AiServiceClient aiServiceClient;

    @Mock
    private MajorDatasetContextService majorDatasetContextService;

    private ResultService resultService;

    @BeforeEach
    void setUp() {
        resultService = new ResultService(
                diagnosisResultRepository,
                resultMajorScoreRepository,
                diagnosisSessionRepository,
                competencyEvalResultRepository,
                majorRepository,
                aiServiceClient,
                majorDatasetContextService
        );
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
    void generateAiCommentRequestContainsOnlyPrimaryMajor() {
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
        assertThat(request.recommendationGroups()).isEmpty();
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
        when(majorRepository.findAllById(List.of(201L))).thenReturn(List.of(topMajor));
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
        assertThat(request.recommendationGroups()).isEmpty();
        assertThat(result.getAiComment()).isEqualTo("primary summary");
        assertThat(result.getAiCommentStatus()).isEqualTo(AiGenerationStatus.SUCCEEDED);
        assertThat(rankOneScore.getRecommendationReason()).isEqualTo("primary reason");
        assertThat(rankTwoScore.getRecommendationReason()).isNull();
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

    private DiagnosisResult diagnosisResult(Long id, Long sessionId, Long userId) {
        DiagnosisResult result = newEntity(DiagnosisResult.class);
        ReflectionTestUtils.setField(result, "id", id);
        ReflectionTestUtils.setField(result, "diagnosisSessionId", sessionId);
        ReflectionTestUtils.setField(result, "userId", userId);
        ReflectionTestUtils.setField(result, "competencyVector", "{}");
        ReflectionTestUtils.setField(result, "tendencyVector", "{}");
        return result;
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

    private Major major(Long id, String name) {
        Major major = newEntity(Major.class);
        ReflectionTestUtils.setField(major, "id", id);
        ReflectionTestUtils.setField(major, "name", name);
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
