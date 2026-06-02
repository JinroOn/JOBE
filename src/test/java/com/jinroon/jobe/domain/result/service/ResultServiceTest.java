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
    }

    @Test
    void generateAiCommentStoresMatchedMajorCommentsAndSkipsUnmatchedComments() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore firstScore = resultMajorScore(11L, 1L, 100L, 1, 87.5f);
        ResultMajorScore secondScore = resultMajorScore(12L, 1L, 101L, 2, 82.0f);
        Major firstMajor = major(100L, "Major A");
        Major secondMajor = major(101L, "Major B");
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
        when(majorRepository.findAllById(List.of(100L, 101L))).thenReturn(List.of(firstMajor, secondMajor));
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
    }

    @Test
    void generateAiCommentRequestContainsRecommendationGroups() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L);
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore firstScore = resultMajorScore(11L, 1L, 100L, 1, 87.5f);
        ResultMajorScore secondScore = resultMajorScore(12L, 1L, 101L, 2, 82.0f);
        Major computerScience = major(100L, "컴퓨터공학과", "공학", 90.0f, 85.0f, 50.0f);
        Major dataScience = major(101L, "데이터사이언스학과", "공학", 70.0f, 60.0f, 95.0f);
        RecommendationCommentResponse response = new RecommendationCommentResponse(
                "AI 요약",
                List.of(
                        new RecommendationCommentResponse.MajorComment(
                                "컴퓨터공학과",
                                1,
                                87.5,
                                "구현력",
                                "의사소통",
                                "구현력이 강해 잘 맞습니다."
                        ),
                        new RecommendationCommentResponse.MajorComment(
                                "데이터사이언스학과",
                                2,
                                82.0,
                                "데이터분석",
                                "협업",
                                "데이터 분석 역량을 활용할 수 있습니다."
                        )
                ),
                List.of("communicationScore"),
                "rec-comment-v1.2.0",
                "request-1"
        );

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(1L))
                .thenReturn(List.of(firstScore, secondScore));
        when(majorRepository.findAllById(List.of(100L, 101L))).thenReturn(List.of(computerScience, dataScience));
        when(majorDatasetContextService.toRecommendationMajorContext(computerScience))
                .thenReturn(new RecommendationCommentRequest.MajorContext(
                        "공학",
                        "컴퓨터공학과 설명",
                        "컴퓨터공학과 근거",
                        List.of("소프트웨어 개발자"),
                        List.of("컴퓨터공학과 RAG snippet")
                ));
        when(majorDatasetContextService.toRecommendationMajorContext(dataScience))
                .thenReturn(new RecommendationCommentRequest.MajorContext(
                        "공학",
                        "데이터사이언스학과 설명",
                        "데이터사이언스학과 근거",
                        List.of("데이터 분석가"),
                        List.of("데이터사이언스학과 RAG snippet")
                ));
        when(aiServiceClient.getRecommendationComment(any(RecommendationCommentRequest.class))).thenReturn(response);

        resultService.generateAiCommentForUser(1L, 7L);

        ArgumentCaptor<RecommendationCommentRequest> requestCaptor =
                ArgumentCaptor.forClass(RecommendationCommentRequest.class);
        verify(aiServiceClient).getRecommendationComment(requestCaptor.capture());
        RecommendationCommentRequest request = requestCaptor.getValue();

        assertThat(request.recommendationGroups()).hasSize(1);
        RecommendationCommentRequest.RecommendationGroup group = request.recommendationGroups().get(0);
        assertThat(group.groupOrder()).isEqualTo(1);
        assertThat(group.representativeMajorName()).isEqualTo("컴퓨터공학과");
        assertThat(group.representativeRankingOrder()).isEqualTo(1);
        assertThat(group.similarMajorNames()).containsExactly("데이터사이언스학과");
        assertThat(group.commonFitAxes()).contains("softwareImplementationScore", "mathLogicalScore");
        assertThat(group.differencePoints()).hasSize(2);
        assertThat(group.differencePoints())
                .extracting(RecommendationCommentRequest.DifferencePoint::majorName)
                .containsExactly("컴퓨터공학과", "데이터사이언스학과");
        assertThat(group.differencePoints().get(0).description()).contains("컴퓨터공학과", "구현력", "시스템이해");
        assertThat(request.topMajors().get(0).majorContext()).isNotNull();
        assertThat(request.topMajors().get(0).majorContext().ragSnippets()).containsExactly("컴퓨터공학과 RAG snippet");
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
