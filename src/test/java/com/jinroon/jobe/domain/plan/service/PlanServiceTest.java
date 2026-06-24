package com.jinroon.jobe.domain.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.diagnosis.entity.CompetencyEvalResult;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.diagnosis.repository.CompetencyEvalResultRepository;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisSessionRepository;
import com.jinroon.jobe.domain.diagnosis.service.DiagnosisProfileScoringService;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
import com.jinroon.jobe.domain.major.service.MajorDatasetContextService;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlan;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlanItem;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlanRiskNote;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanItemRepository;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanRepository;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanRiskNoteRepository;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.entity.ResultMajorScore;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.domain.result.repository.ResultMajorScoreRepository;
import com.jinroon.jobe.global.client.AiServiceClient;
import com.jinroon.jobe.global.client.dto.request.WeeklyPlanRequest;
import com.jinroon.jobe.global.client.dto.response.WeeklyPlanResponse;
import com.jinroon.jobe.global.common.ai.AiGenerationStatus;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PlanServiceTest {

    @Mock
    private MajorWeeklyPlanRepository planRepository;

    @Mock
    private MajorWeeklyPlanItemRepository planItemRepository;

    @Mock
    private MajorWeeklyPlanRiskNoteRepository riskNoteRepository;

    @Mock
    private DiagnosisResultRepository diagnosisResultRepository;

    @Mock
    private ResultMajorScoreRepository resultMajorScoreRepository;

    @Mock
    private CompetencyEvalResultRepository competencyEvalResultRepository;

    @Mock
    private DiagnosisSessionRepository diagnosisSessionRepository;

    @Mock
    private MajorRepository majorRepository;

    private DiagnosisProfileScoringService diagnosisProfileScoringService;

    @Mock
    private AiServiceClient aiServiceClient;

    @Mock
    private MajorDatasetContextService majorDatasetContextService;

    private PlanService planService;

    @BeforeEach
    void setUp() {
        diagnosisProfileScoringService = new DiagnosisProfileScoringService(new ObjectMapper());
        planService = new PlanService(
                planRepository,
                planItemRepository,
                riskNoteRepository,
                diagnosisResultRepository,
                resultMajorScoreRepository,
                competencyEvalResultRepository,
                diagnosisSessionRepository,
                majorRepository,
                diagnosisProfileScoringService,
                aiServiceClient,
                new ObjectMapper(),
                majorDatasetContextService
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void createPlanStoresAiWeeklyPlanItemsAsJsonArrays() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L, "communicationScore");
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore score = resultMajorScore(100L, 87.5f);
        Major major = major(100L, "컴퓨터공학과");
        WeeklyPlanResponse response = new WeeklyPlanResponse(
                "plan-ai-1",
                "AI 주간 학습 계획 개요",
                List.of(
                        new WeeklyPlanResponse.WeeklyPlan(
                                1,
                                "기초 다지기",
                                List.of("기초 문법 복습", "알고리즘 문제 풀이"),
                                List.of("공식 문서", "문제 풀이 사이트"),
                                "간단한 문제 5개 해결"
                        )
                ),
                List.of("학습 시간이 부족하면 범위를 줄인다."),
                "plan-v1.0.0",
                "request-plan-1"
        );

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(planRepository.save(any(MajorWeeklyPlan.class))).thenAnswer(invocation -> {
            MajorWeeklyPlan plan = invocation.getArgument(0);
            ReflectionTestUtils.setField(plan, "id", 20L);
            return plan;
        });
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(diagnosisSessionRepository.findById(10L)).thenReturn(Optional.of(diagnosisSession("""
                {
                  "grade": "1학년",
                  "dreamJob": "AI 데이터 사이언티스트",
                  "studyHours": 4.5,
                  "selectedSubjects": ["정보/코딩"],
                  "learningStyle": "practice",
                  "aspiration": "데이터로 문제를 해결하고 싶다"
                }
                """)));
        when(resultMajorScoreRepository.findById(11L)).thenReturn(Optional.of(score));
        when(majorRepository.findById(100L)).thenReturn(Optional.of(major));
        when(majorDatasetContextService.toWeeklyPlanMajorContext(major))
                .thenReturn(new WeeklyPlanRequest.MajorContext(
                        "공학",
                        "컴퓨터공학과 설명",
                        "컴퓨터공학과 근거",
                        List.of("소프트웨어 개발자"),
                        List.of("컴퓨터공학과 RAG snippet")
                ));
        when(aiServiceClient.getWeeklyPlan(any(WeeklyPlanRequest.class))).thenReturn(response);

        MajorWeeklyPlan plan = planService.createPlanForUser(planValues(), 7L);

        assertThat(plan.getPlanId()).isEqualTo("plan-ai-1");
        assertThat(plan.getOverview()).isEqualTo("AI 주간 학습 계획 개요");
        assertThat(plan.getVersionNo()).isEqualTo(1);
        assertThat(plan.getParentPlanId()).isNull();
        assertThat(plan.getActiveVersion()).isTrue();
        assertThat(plan.getAiPlanStatus()).isEqualTo(AiGenerationStatus.SUCCEEDED);
        assertThat(plan.getAiPlanErrorMessage()).isNull();
        assertThat(plan.getAiPlanRequestedAt()).isNotNull();
        assertThat(plan.getAiPlanCompletedAt()).isNotNull();

        ArgumentCaptor<List<MajorWeeklyPlanItem>> itemCaptor = ArgumentCaptor.forClass(List.class);
        verify(planItemRepository).saveAll(itemCaptor.capture());
        List<MajorWeeklyPlanItem> items = itemCaptor.getValue();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getTasksJson()).isEqualTo("[\"기초 문법 복습\",\"알고리즘 문제 풀이\"]");
        assertThat(items.get(0).getResourcesJson()).isEqualTo("[\"공식 문서\",\"문제 풀이 사이트\"]");

        ArgumentCaptor<MajorWeeklyPlanRiskNote> riskNoteCaptor =
                ArgumentCaptor.forClass(MajorWeeklyPlanRiskNote.class);
        verify(riskNoteRepository).save(riskNoteCaptor.capture());
        assertThat(riskNoteCaptor.getValue().getNote()).isEqualTo("학습 시간이 부족하면 범위를 줄인다.");

        ArgumentCaptor<WeeklyPlanRequest> requestCaptor = ArgumentCaptor.forClass(WeeklyPlanRequest.class);
        verify(aiServiceClient).getWeeklyPlan(requestCaptor.capture());
        assertThat(requestCaptor.getValue().targetMajor().majorContext()).isNotNull();
        assertThat(requestCaptor.getValue().targetMajor().majorContext().ragSnippets())
                .containsExactly("컴퓨터공학과 RAG snippet");
        assertThat(requestCaptor.getValue().profileContext()).isNotNull();
        assertThat(requestCaptor.getValue().profileContext().dreamJob()).isEqualTo("AI 데이터 사이언티스트");
        assertThat(requestCaptor.getValue().constraints().studyHoursPerWeek()).isEqualTo(32);
    }

    @Test
    void createPlanReturnsPlanWithoutItemsWhenAiServiceReturnsNull() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L, null);
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore score = resultMajorScore(100L, 87.5f);
        Major major = major(100L, "컴퓨터공학과");

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(planRepository.save(any(MajorWeeklyPlan.class))).thenAnswer(invocation -> {
            MajorWeeklyPlan plan = invocation.getArgument(0);
            ReflectionTestUtils.setField(plan, "id", 20L);
            return plan;
        });
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findById(11L)).thenReturn(Optional.of(score));
        when(majorRepository.findById(100L)).thenReturn(Optional.of(major));
        when(aiServiceClient.getWeeklyPlan(any(WeeklyPlanRequest.class))).thenReturn(null);

        MajorWeeklyPlan plan = planService.createPlanForUser(planValues(), 7L);

        assertThat(plan.getPlanId()).isNull();
        assertThat(plan.getVersionNo()).isEqualTo(1);
        assertThat(plan.getParentPlanId()).isNull();
        assertThat(plan.getActiveVersion()).isFalse();
        assertThat(plan.getAiPlanStatus()).isEqualTo(AiGenerationStatus.FAILED);
        assertThat(plan.getAiPlanErrorMessage()).isEqualTo("ai-service weekly plan response is null");
        assertThat(plan.getAiPlanRequestedAt()).isNotNull();
        assertThat(plan.getAiPlanCompletedAt()).isNotNull();
        verify(planItemRepository, never()).saveAll(any());
        verify(riskNoteRepository, never()).save(any());
    }

    @Test
    void createPlanMarksFailedWhenAiResponseHasNoWeeklyItems() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L, null);
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore score = resultMajorScore(100L, 87.5f);
        Major major = major(100L, "컴퓨터공학과");
        WeeklyPlanResponse response = new WeeklyPlanResponse(
                "plan-ai-empty",
                "overview only",
                List.of(),
                List.of(),
                "plan-v1.0.0",
                "request-plan-empty"
        );

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(planRepository.save(any(MajorWeeklyPlan.class))).thenAnswer(invocation -> {
            MajorWeeklyPlan plan = invocation.getArgument(0);
            ReflectionTestUtils.setField(plan, "id", 20L);
            return plan;
        });
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findById(11L)).thenReturn(Optional.of(score));
        when(majorRepository.findById(100L)).thenReturn(Optional.of(major));
        when(aiServiceClient.getWeeklyPlan(any(WeeklyPlanRequest.class))).thenReturn(response);

        MajorWeeklyPlan plan = planService.createPlanForUser(planValues(), 7L);

        assertThat(plan.getPlanId()).isEqualTo("plan-ai-empty");
        assertThat(plan.getOverview()).isEqualTo("overview only");
        assertThat(plan.getActiveVersion()).isFalse();
        assertThat(plan.getAiPlanStatus()).isEqualTo(AiGenerationStatus.FAILED);
        assertThat(plan.getAiPlanErrorMessage()).isEqualTo("ai-service weekly plan response has no weekly items");
        verify(planItemRepository, never()).saveAll(any());
        verify(riskNoteRepository, never()).save(any());
    }

    @Test
    void createPlanDoesNotCallAiWhenMajorScoreBelongsToDifferentResult() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L, null);
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore score = resultMajorScore(100L, 87.5f);
        ReflectionTestUtils.setField(score, "diagnosisResultId", 99L);

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(planRepository.save(any(MajorWeeklyPlan.class))).thenAnswer(invocation -> {
            MajorWeeklyPlan plan = invocation.getArgument(0);
            ReflectionTestUtils.setField(plan, "id", 20L);
            return plan;
        });
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findById(11L)).thenReturn(Optional.of(score));

        MajorWeeklyPlan plan = planService.createPlanForUser(planValues(), 7L);

        assertThat(plan.getPlanId()).isNull();
        assertThat(plan.getOverview()).isNull();
        assertThat(plan.getActiveVersion()).isFalse();
        assertThat(plan.getAiPlanStatus()).isEqualTo(AiGenerationStatus.SKIPPED);
        assertThat(plan.getAiPlanErrorMessage()).isEqualTo("result major score does not belong to diagnosis result");
        assertThat(plan.getAiPlanRequestedAt()).isNotNull();
        assertThat(plan.getAiPlanCompletedAt()).isNotNull();
        verify(majorRepository, never()).findById(any());
        verify(aiServiceClient, never()).getWeeklyPlan(any());
        verify(planItemRepository, never()).saveAll(any());
        verify(riskNoteRepository, never()).save(any());
    }

    @Test
    void createPlanForUserRejectsOtherUsersResult() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L, null);
        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));

        assertThatThrownBy(() -> planService.createPlanForUser(planValues(), 99L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(planRepository, never()).save(any());
        verify(aiServiceClient, never()).getWeeklyPlan(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void createPlanCreatesNextVersionWhenActiveSucceededPlanExists() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L, null);
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore score = resultMajorScore(100L, 87.5f);
        Major major = major(100L, "Major A");
        MajorWeeklyPlan existingPlan = weeklyPlan(20L, 1L, 11L);
        existingPlan.markAiPlanSucceeded();
        WeeklyPlanResponse response = new WeeklyPlanResponse(
                "plan-ai-v2",
                "version 2 overview",
                List.of(new WeeklyPlanResponse.WeeklyPlan(
                        1,
                        "goal",
                        List.of("task"),
                        List.of("resource"),
                        "checkpoint"
                )),
                List.of("risk"),
                "plan-v1.0.0",
                "request-plan-v2"
        );

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(planRepository.findByResultMajorScoreIdAndActiveVersionTrue(11L)).thenReturn(Optional.of(existingPlan));
        when(planRepository.findTopByResultMajorScoreIdOrderByVersionNoDesc(11L)).thenReturn(Optional.of(existingPlan));
        when(planRepository.save(any(MajorWeeklyPlan.class))).thenAnswer(invocation -> {
            MajorWeeklyPlan plan = invocation.getArgument(0);
            ReflectionTestUtils.setField(plan, "id", 21L);
            return plan;
        });
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findById(11L)).thenReturn(Optional.of(score));
        when(majorRepository.findById(100L)).thenReturn(Optional.of(major));
        when(aiServiceClient.getWeeklyPlan(any(WeeklyPlanRequest.class))).thenReturn(response);

        MajorWeeklyPlan newPlan = planService.createPlanForUser(planValues(), 7L);

        assertThat(existingPlan.getActiveVersion()).isFalse();
        assertThat(newPlan.getId()).isEqualTo(21L);
        assertThat(newPlan.getVersionNo()).isEqualTo(2);
        assertThat(newPlan.getParentPlanId()).isEqualTo(20L);
        assertThat(newPlan.getActiveVersion()).isTrue();
        assertThat(newPlan.getAiPlanStatus()).isEqualTo(AiGenerationStatus.SUCCEEDED);
        ArgumentCaptor<List<MajorWeeklyPlanItem>> itemCaptor = ArgumentCaptor.forClass(List.class);
        verify(planItemRepository).saveAll(itemCaptor.capture());
        assertThat(itemCaptor.getValue()).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createPlanDeactivatesAllActivePlansForSameDiagnosisResultWhenNewPlanSucceeds() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L, null);
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore score = resultMajorScore(100L, 87.5f);
        Major major = major(100L, "Major A");
        MajorWeeklyPlan existingSameScorePlan = weeklyPlan(20L, 1L, 11L);
        existingSameScorePlan.markAiPlanSucceeded();
        MajorWeeklyPlan existingOtherScorePlan = weeklyPlan(22L, 1L, 12L);
        existingOtherScorePlan.markAiPlanSucceeded();
        WeeklyPlanResponse response = new WeeklyPlanResponse(
                "plan-ai-v2",
                "version 2 overview",
                List.of(new WeeklyPlanResponse.WeeklyPlan(
                        1,
                        "goal",
                        List.of("task"),
                        List.of("resource"),
                        "checkpoint"
                )),
                List.of(),
                "plan-v1.0.0",
                "request-plan-v2"
        );

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(planRepository.findByDiagnosisResultIdAndActiveVersionTrue(1L))
                .thenReturn(List.of(existingSameScorePlan, existingOtherScorePlan));
        when(planRepository.findByResultMajorScoreIdAndActiveVersionTrue(11L)).thenReturn(Optional.of(existingSameScorePlan));
        when(planRepository.findTopByResultMajorScoreIdOrderByVersionNoDesc(11L)).thenReturn(Optional.of(existingSameScorePlan));
        when(planRepository.save(any(MajorWeeklyPlan.class))).thenAnswer(invocation -> {
            MajorWeeklyPlan plan = invocation.getArgument(0);
            ReflectionTestUtils.setField(plan, "id", 21L);
            return plan;
        });
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findById(11L)).thenReturn(Optional.of(score));
        when(majorRepository.findById(100L)).thenReturn(Optional.of(major));
        when(aiServiceClient.getWeeklyPlan(any(WeeklyPlanRequest.class))).thenReturn(response);

        MajorWeeklyPlan newPlan = planService.createPlanForUser(planValues(), 7L);

        assertThat(existingSameScorePlan.getActiveVersion()).isFalse();
        assertThat(existingOtherScorePlan.getActiveVersion()).isFalse();
        assertThat(newPlan.getActiveVersion()).isTrue();
        assertThat(newPlan.getAiPlanStatus()).isEqualTo(AiGenerationStatus.SUCCEEDED);
        ArgumentCaptor<List<MajorWeeklyPlanItem>> itemCaptor = ArgumentCaptor.forClass(List.class);
        verify(planItemRepository).saveAll(itemCaptor.capture());
        assertThat(itemCaptor.getValue()).hasSize(1);
    }

    @Test
    void createPlanBlocksDuplicateWhenActivePlanIsPending() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L, null);
        MajorWeeklyPlan existingPlan = weeklyPlan(20L, 1L, 11L);
        existingPlan.markAiPlanPending();

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(planRepository.findByResultMajorScoreIdAndActiveVersionTrue(11L)).thenReturn(Optional.of(existingPlan));

        assertThatThrownBy(() -> planService.createPlanForUser(planValues(), 7L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AI_PLAN_ALREADY_EXISTS);

        verify(planRepository, never()).save(any());
        verify(aiServiceClient, never()).getWeeklyPlan(any());
    }

    @Test
    void createPlanBlocksDuplicateWhenAnyActivePlanInSameDiagnosisResultIsPending() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L, null);
        MajorWeeklyPlan pendingPlan = weeklyPlan(30L, 1L, 12L);
        pendingPlan.markAiPlanPending();

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(planRepository.findByDiagnosisResultIdAndActiveVersionTrue(1L)).thenReturn(List.of(pendingPlan));

        assertThatThrownBy(() -> planService.createPlanForUser(planValues(), 7L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.AI_PLAN_ALREADY_EXISTS);

        verify(planRepository, never()).save(any());
        verify(aiServiceClient, never()).getWeeklyPlan(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void createPlanAllowsRetryWhenActivePlanFailed() {
        DiagnosisResult result = diagnosisResult(1L, 10L, 7L, null);
        CompetencyEvalResult competency = competencyResult(10L);
        ResultMajorScore score = resultMajorScore(100L, 87.5f);
        Major major = major(100L, "Major A");
        MajorWeeklyPlan failedPlan = weeklyPlan(20L, 1L, 11L);
        failedPlan.markAiPlanFailed("previous failure");
        WeeklyPlanResponse response = new WeeklyPlanResponse(
                "plan-ai-retry",
                "retried overview",
                List.of(new WeeklyPlanResponse.WeeklyPlan(
                        1,
                        "goal",
                        List.of("task"),
                        List.of("resource"),
                        "checkpoint"
                )),
                List.of("risk"),
                "plan-v1.0.0",
                "request-plan-retry"
        );

        when(diagnosisResultRepository.findById(1L)).thenReturn(Optional.of(result));
        when(planRepository.findByResultMajorScoreIdAndActiveVersionTrue(11L)).thenReturn(Optional.of(failedPlan));
        when(planRepository.findTopByResultMajorScoreIdOrderByVersionNoDesc(11L)).thenReturn(Optional.of(failedPlan));
        when(planRepository.save(any(MajorWeeklyPlan.class))).thenAnswer(invocation -> {
            MajorWeeklyPlan plan = invocation.getArgument(0);
            ReflectionTestUtils.setField(plan, "id", 21L);
            return plan;
        });
        when(competencyEvalResultRepository.findByDiagnosisSessionId(10L)).thenReturn(Optional.of(competency));
        when(resultMajorScoreRepository.findById(11L)).thenReturn(Optional.of(score));
        when(majorRepository.findById(100L)).thenReturn(Optional.of(major));
        when(aiServiceClient.getWeeklyPlan(any(WeeklyPlanRequest.class))).thenReturn(response);

        MajorWeeklyPlan retriedPlan = planService.createPlanForUser(planValues(), 7L);

        assertThat(retriedPlan.getId()).isEqualTo(21L);
        assertThat(failedPlan.getActiveVersion()).isFalse();
        assertThat(retriedPlan.getVersionNo()).isEqualTo(2);
        assertThat(retriedPlan.getParentPlanId()).isEqualTo(20L);
        assertThat(retriedPlan.getActiveVersion()).isTrue();
        assertThat(retriedPlan.getPlanId()).isEqualTo("plan-ai-retry");
        assertThat(retriedPlan.getAiPlanStatus()).isEqualTo(AiGenerationStatus.SUCCEEDED);
        ArgumentCaptor<List<MajorWeeklyPlanItem>> itemCaptor = ArgumentCaptor.forClass(List.class);
        verify(planItemRepository).saveAll(itemCaptor.capture());
        assertThat(itemCaptor.getValue()).hasSize(1);
    }

    private Map<String, Object> planValues() {
        return Map.of(
                "diagnosisResultId", 1L,
                "resultMajorScoreId", 11L,
                "versionNo", 1,
                "fallback", false,
                "activeVersion", true
        );
    }

    private DiagnosisResult diagnosisResult(Long id, Long sessionId, Long userId, String weaknessFocus) {
        DiagnosisResult result = newEntity(DiagnosisResult.class);
        ReflectionTestUtils.setField(result, "id", id);
        ReflectionTestUtils.setField(result, "diagnosisSessionId", sessionId);
        ReflectionTestUtils.setField(result, "userId", userId);
        ReflectionTestUtils.setField(result, "competencyVector", "{}");
        ReflectionTestUtils.setField(result, "tendencyVector", "{}");
        ReflectionTestUtils.setField(result, "weaknessFocus", weaknessFocus);
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

    private ResultMajorScore resultMajorScore(Long majorId, Float finalScore) {
        ResultMajorScore score = newEntity(ResultMajorScore.class);
        ReflectionTestUtils.setField(score, "id", 11L);
        ReflectionTestUtils.setField(score, "diagnosisResultId", 1L);
        ReflectionTestUtils.setField(score, "majorId", majorId);
        ReflectionTestUtils.setField(score, "rank", 1);
        ReflectionTestUtils.setField(score, "finalScore", finalScore);
        ReflectionTestUtils.setField(score, "failed", false);
        return score;
    }

    private DiagnosisSession diagnosisSession(String inputSnapshot) {
        DiagnosisSession session = newEntity(DiagnosisSession.class);
        ReflectionTestUtils.setField(session, "id", 10L);
        ReflectionTestUtils.setField(session, "inputSnapshot", inputSnapshot);
        return session;
    }

    private Major major(Long id, String name) {
        Major major = newEntity(Major.class);
        ReflectionTestUtils.setField(major, "id", id);
        ReflectionTestUtils.setField(major, "name", name);
        return major;
    }

    private MajorWeeklyPlan weeklyPlan(Long id, Long resultId, Long resultMajorScoreId) {
        MajorWeeklyPlan plan = newEntity(MajorWeeklyPlan.class);
        ReflectionTestUtils.setField(plan, "id", id);
        ReflectionTestUtils.setField(plan, "diagnosisResultId", resultId);
        ReflectionTestUtils.setField(plan, "resultMajorScoreId", resultMajorScoreId);
        ReflectionTestUtils.setField(plan, "versionNo", 1);
        ReflectionTestUtils.setField(plan, "fallback", false);
        ReflectionTestUtils.setField(plan, "activeVersion", true);
        return plan;
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
