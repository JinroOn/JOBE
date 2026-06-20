package com.jinroon.jobe.domain.plan.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.client.AiServiceClient;
import com.jinroon.jobe.global.client.dto.request.WeeklyPlanRequest;
import com.jinroon.jobe.global.client.dto.request.WeeklyPlanRequest.Constraints;
import com.jinroon.jobe.global.client.dto.request.WeeklyPlanRequest.Profile;
import com.jinroon.jobe.global.client.dto.request.WeeklyPlanRequest.TargetMajor;
import com.jinroon.jobe.global.client.dto.response.WeeklyPlanResponse;
import com.jinroon.jobe.global.common.ai.AiGenerationStatus;
import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.domain.diagnosis.entity.CompetencyEvalResult;
import com.jinroon.jobe.domain.diagnosis.repository.CompetencyEvalResultRepository;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PlanService {

    private final MajorWeeklyPlanRepository planRepository;
    private final MajorWeeklyPlanItemRepository planItemRepository;
    private final MajorWeeklyPlanRiskNoteRepository riskNoteRepository;
    private final DiagnosisResultRepository diagnosisResultRepository;
    private final ResultMajorScoreRepository resultMajorScoreRepository;
    private final CompetencyEvalResultRepository competencyEvalResultRepository;
    private final MajorRepository majorRepository;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;
    private final MajorDatasetContextService majorDatasetContextService;

    public MajorWeeklyPlan getPlan(Long planId) {
        return get(planRepository, planId, ErrorCode.PLAN_NOT_FOUND);
    }

    public MajorWeeklyPlan getPlanForUser(Long planId, Long userId) {
        MajorWeeklyPlan plan = getPlan(planId);
        requireResultOwner(plan.getDiagnosisResultId(), userId);
        return plan;
    }

    public List<MajorWeeklyPlan> findPlansByResult(Long resultId) {
        return planRepository.findByDiagnosisResultId(resultId);
    }

    public List<MajorWeeklyPlan> findPlansByResultForUser(Long resultId, Long userId) {
        requireResultOwner(resultId, userId);
        return findPlansByResult(resultId);
    }

    public List<MajorWeeklyPlanItem> findItems(Long planId) {
        return planItemRepository.findByWeeklyPlanIdOrderByWeekNoAsc(planId);
    }

    public List<MajorWeeklyPlanItem> findItemsForUser(Long planId, Long userId) {
        getPlanForUser(planId, userId);
        return findItems(planId);
    }

    public List<MajorWeeklyPlanRiskNote> findRiskNotes(Long planId) {
        return riskNoteRepository.findByWeeklyPlanId(planId);
    }

    public List<MajorWeeklyPlanRiskNote> findRiskNotesForUser(Long planId, Long userId) {
        getPlanForUser(planId, userId);
        return findRiskNotes(planId);
    }

    @Transactional
    public MajorWeeklyPlan createPlan(Map<String, Object> values) {
        Long diagnosisResultId = ((Number) values.get("diagnosisResultId")).longValue();
        Long resultMajorScoreId = ((Number) values.get("resultMajorScoreId")).longValue();
        List<MajorWeeklyPlan> activePlansByResult = activePlansByDiagnosisResult(diagnosisResultId);
        blockIfPendingActivePlanExists(diagnosisResultId, activePlansByResult);
        PlanVersionContext versionContext = prepareNextVersion(resultMajorScoreId);

        Map<String, Object> planValues = new HashMap<>(values);
        planValues.put("versionNo", versionContext.versionNo());
        planValues.put("parentPlanId", versionContext.parentPlanId());
        planValues.put("activeVersion", true);
        if (planValues.get("fallback") == null) {
            planValues.put("fallback", false);
        }

        MajorWeeklyPlan plan = EntityFormMapper.create(MajorWeeklyPlan.class, planValues);
        plan.prepareVersion(versionContext.versionNo(), versionContext.parentPlanId());
        activePlansByResult.forEach(MajorWeeklyPlan::deactivate);
        versionContext.activePlan().ifPresent(MajorWeeklyPlan::deactivate);
        plan = planRepository.save(plan);

        try {
            applyAiWeeklyPlan(plan);
        } catch (Exception e) {
            plan.markAiPlanFailed("AI weekly plan apply failed: " + e.getClass().getSimpleName());
            log.warn(
                    "AI weekly plan apply failed planId={} diagnosisResultId={} resultMajorScoreId={} errorType={} message={}",
                    plan.getId(),
                    plan.getDiagnosisResultId(),
                    plan.getResultMajorScoreId(),
                    e.getClass().getSimpleName(),
                    e.getMessage()
            );
        }
        if (plan.getAiPlanStatus() != AiGenerationStatus.SUCCEEDED) {
            plan.deactivate();
        }

        return plan;
    }

    @Transactional
    public MajorWeeklyPlan createPlanForUser(Map<String, Object> values, Long userId) {
        Long resultId = ((Number) values.get("diagnosisResultId")).longValue();
        requireResultOwner(resultId, userId);
        return createPlan(values);
    }

    @Transactional
    public MajorWeeklyPlan updatePlan(Long planId, Map<String, Object> values) {
        MajorWeeklyPlan plan = getPlan(planId);
        EntityFormMapper.apply(plan, values);
        return plan;
    }

    @Transactional
    public MajorWeeklyPlan updatePlanForUser(Long planId, Map<String, Object> values, Long userId) {
        MajorWeeklyPlan plan = getPlanForUser(planId, userId);
        values.remove("diagnosisResultId");
        EntityFormMapper.apply(plan, values);
        return plan;
    }

    @Transactional
    public MajorWeeklyPlanItem createItem(Map<String, Object> values) {
        return planItemRepository.save(EntityFormMapper.create(MajorWeeklyPlanItem.class, values));
    }

    @Transactional
    public MajorWeeklyPlanItem createItemForUser(Map<String, Object> values, Long userId) {
        Long planId = ((Number) values.get("weeklyPlanId")).longValue();
        getPlanForUser(planId, userId);
        return createItem(values);
    }

    @Transactional
    public MajorWeeklyPlanRiskNote createRiskNote(Map<String, Object> values) {
        return riskNoteRepository.save(EntityFormMapper.create(MajorWeeklyPlanRiskNote.class, values));
    }

    @Transactional
    public MajorWeeklyPlanRiskNote createRiskNoteForUser(Map<String, Object> values, Long userId) {
        Long planId = ((Number) values.get("weeklyPlanId")).longValue();
        getPlanForUser(planId, userId);
        return createRiskNote(values);
    }

    @Transactional
    public void completeItem(Long itemId) {
        MajorWeeklyPlanItem item = get(planItemRepository, itemId, ErrorCode.PLAN_ITEM_NOT_FOUND);
        item.complete();
    }

    @Transactional
    public void completeItemForUser(Long itemId, Long userId) {
        MajorWeeklyPlanItem item = get(planItemRepository, itemId, ErrorCode.PLAN_ITEM_NOT_FOUND);
        getPlanForUser(item.getWeeklyPlanId(), userId);
        item.complete();
    }

    private void requireResultOwner(Long resultId, Long userId) {
        DiagnosisResult result = get(diagnosisResultRepository, resultId, ErrorCode.RESULT_NOT_FOUND);
        if (!Objects.equals(result.getUserId(), userId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }

    private PlanVersionContext prepareNextVersion(Long resultMajorScoreId) {
        Optional<MajorWeeklyPlan> activePlan = Optional
                .ofNullable(planRepository.findByResultMajorScoreIdAndActiveVersionTrue(resultMajorScoreId))
                .orElse(Optional.empty());
        activePlan
                .filter(plan -> plan.getAiPlanStatus() == AiGenerationStatus.PENDING)
                .ifPresent(plan -> {
                    log.warn("AI weekly plan duplicate creation blocked resultMajorScoreId={} existingPlanId={} status={}",
                            resultMajorScoreId, plan.getId(), plan.getAiPlanStatus());
                    throw new CustomException(ErrorCode.AI_PLAN_ALREADY_EXISTS);
                });

        Optional<MajorWeeklyPlan> latestPlan = Optional
                .ofNullable(planRepository.findTopByResultMajorScoreIdOrderByVersionNoDesc(resultMajorScoreId))
                .orElse(Optional.empty());
        int nextVersionNo = latestPlan
                .map(MajorWeeklyPlan::getVersionNo)
                .filter(Objects::nonNull)
                .map(versionNo -> versionNo + 1)
                .orElse(1);
        Long parentPlanId = latestPlan.map(MajorWeeklyPlan::getId).orElse(null);
        return new PlanVersionContext(nextVersionNo, parentPlanId, activePlan);
    }

    private List<MajorWeeklyPlan> activePlansByDiagnosisResult(Long diagnosisResultId) {
        List<MajorWeeklyPlan> activePlans = planRepository.findByDiagnosisResultIdAndActiveVersionTrue(diagnosisResultId);
        return activePlans != null ? activePlans : List.of();
    }

    private void blockIfPendingActivePlanExists(Long diagnosisResultId, List<MajorWeeklyPlan> activePlans) {
        activePlans.stream()
                .filter(plan -> plan.getAiPlanStatus() == AiGenerationStatus.PENDING)
                .findFirst()
                .ifPresent(plan -> {
                    log.warn("AI weekly plan duplicate creation blocked diagnosisResultId={} existingPlanId={} status={}",
                            diagnosisResultId, plan.getId(), plan.getAiPlanStatus());
                    throw new CustomException(ErrorCode.AI_PLAN_ALREADY_EXISTS);
                });
    }

    private void applyAiWeeklyPlan(MajorWeeklyPlan plan) {
        plan.markAiPlanPending();
        Optional<DiagnosisResult> resultOpt =
                diagnosisResultRepository.findById(plan.getDiagnosisResultId());
        if (resultOpt.isEmpty()) {
            plan.markAiPlanSkipped("diagnosis result not found");
            log.warn("AI weekly plan skipped: result not found planId={} diagnosisResultId={}",
                    plan.getId(), plan.getDiagnosisResultId());
            return;
        }
        DiagnosisResult result = resultOpt.get();

        Optional<CompetencyEvalResult> competencyOpt =
                competencyEvalResultRepository.findByDiagnosisSessionId(result.getDiagnosisSessionId());
        if (competencyOpt.isEmpty()) {
            plan.markAiPlanSkipped("competency result not found");
            log.warn("AI weekly plan skipped: competency result not found planId={} diagnosisResultId={} diagnosisSessionId={}",
                    plan.getId(), result.getId(), result.getDiagnosisSessionId());
            return;
        }
        CompetencyEvalResult competency = competencyOpt.get();

        Optional<ResultMajorScore> scoreOpt =
                resultMajorScoreRepository.findById(plan.getResultMajorScoreId());
        if (scoreOpt.isEmpty()) {
            plan.markAiPlanSkipped("result major score not found");
            log.warn("AI weekly plan skipped: major score not found planId={} diagnosisResultId={} resultMajorScoreId={}",
                    plan.getId(), result.getId(), plan.getResultMajorScoreId());
            return;
        }
        ResultMajorScore score = scoreOpt.get();
        if (!Objects.equals(score.getDiagnosisResultId(), result.getId())) {
            plan.markAiPlanSkipped("result major score does not belong to diagnosis result");
            log.warn("AI weekly plan skipped: major score result mismatch planId={} diagnosisResultId={} resultMajorScoreId={} scoreDiagnosisResultId={}",
                    plan.getId(), result.getId(), score.getId(), score.getDiagnosisResultId());
            return;
        }

        Optional<Major> majorOpt = majorRepository.findById(score.getMajorId());
        if (majorOpt.isEmpty()) {
            plan.markAiPlanSkipped("major not found");
            log.warn("AI weekly plan skipped: major not found planId={} diagnosisResultId={} resultMajorScoreId={} majorId={}",
                    plan.getId(), result.getId(), score.getId(), score.getMajorId());
            return;
        }
        Major major = majorOpt.get();
        String majorName = major.getName();

        Profile profile = profileFrom(competency);

        List<String> weaknessFocus = (result.getWeaknessFocus() != null && !result.getWeaknessFocus().isBlank())
                ? Arrays.asList(result.getWeaknessFocus().split(","))
                : List.of();

        WeeklyPlanRequest request = new WeeklyPlanRequest(
                result.getDiagnosisSessionId(),
                new TargetMajor(
                        majorName,
                        score.getFinalScore() != null ? score.getFinalScore().doubleValue() : 0.0,
                        majorDatasetContextService.toWeeklyPlanMajorContext(major)
                ),
                normalizeWeaknessFocus(weaknessFocus, competency),
                profile,
                new Constraints(12, 8, "practice-first")
        );

        WeeklyPlanResponse response = aiServiceClient.getWeeklyPlan(request);
        if (response == null) {
            plan.markAiPlanFailed("ai-service weekly plan response is null");
            log.warn("AI weekly plan response empty planId={} diagnosisResultId={} resultMajorScoreId={} majorId={}",
                    plan.getId(), result.getId(), score.getId(), major.getId());
            return;
        }

        plan.applyAiPlan(response.planId(), response.overview());

        if (response.weeklyPlan() == null || response.weeklyPlan().isEmpty()) {
            plan.markAiPlanFailed("ai-service weekly plan response has no weekly items");
            log.warn("AI weekly plan response has no weekly items planId={} diagnosisResultId={} responsePlanId={}",
                    plan.getId(), result.getId(), response.planId());
            return;
        }

        List<MajorWeeklyPlanItem> items = response.weeklyPlan().stream()
                .map(wp -> MajorWeeklyPlanItem.of(
                        plan.getId(),
                        wp.week(),
                        wp.goal(),
                        toJsonArray(wp.tasks()),
                        toJsonArray(wp.recommendedResources()),
                        wp.checkpoint()
                ))
                .collect(Collectors.toList());
        planItemRepository.saveAll(items);

        if (response.riskNotes() != null) {
            response.riskNotes().stream()
                    .filter(note -> note != null && !note.isBlank())
                    .forEach(note -> riskNoteRepository.save(MajorWeeklyPlanRiskNote.of(plan.getId(), note)));
        }
        plan.markAiPlanSucceeded();
    }

    private Profile profileFrom(CompetencyEvalResult competency) {
        return new Profile(
                safeInt(competency.getMathLogic()),
                safeInt(competency.getProblemSolving()),
                safeInt(competency.getInfoTech()),
                safeInt(competency.getImplementation()),
                safeInt(competency.getSystemUnderstanding()),
                safeInt(competency.getDataAnalysis()),
                safeInt(competency.getCommunication()),
                safeInt(competency.getCollaboration()),
                safeInt(competency.getSelfManagement())
        );
    }

    private List<String> normalizeWeaknessFocus(List<String> weaknessFocus, CompetencyEvalResult competency) {
        List<String> normalized = weaknessFocus == null ? List.of() : weaknessFocus.stream()
                .map(String::trim)
                .filter(this::isValidWeaknessField)
                .distinct()
                .limit(2)
                .toList();
        if (!normalized.isEmpty()) {
            return normalized;
        }
        return weakestFields(competency);
    }

    private boolean isValidWeaknessField(String field) {
        return switch (field) {
            case "mathLogicalScore",
                 "problemSolvingScore",
                 "infoTechUtilizationScore",
                 "softwareImplementationScore",
                 "systemUnderstandingScore",
                 "dataAnalysisScore",
                 "communicationScore",
                 "collaborationScore",
                 "selfManagementScore" -> true;
            default -> false;
        };
    }

    private List<String> weakestFields(CompetencyEvalResult competency) {
        return List.of(
                        new WeaknessField("mathLogicalScore", safeInt(competency.getMathLogic())),
                        new WeaknessField("problemSolvingScore", safeInt(competency.getProblemSolving())),
                        new WeaknessField("infoTechUtilizationScore", safeInt(competency.getInfoTech())),
                        new WeaknessField("softwareImplementationScore", safeInt(competency.getImplementation())),
                        new WeaknessField("systemUnderstandingScore", safeInt(competency.getSystemUnderstanding())),
                        new WeaknessField("dataAnalysisScore", safeInt(competency.getDataAnalysis())),
                        new WeaknessField("communicationScore", safeInt(competency.getCommunication())),
                        new WeaknessField("collaborationScore", safeInt(competency.getCollaboration())),
                        new WeaknessField("selfManagementScore", safeInt(competency.getSelfManagement()))
                )
                .stream()
                .sorted(java.util.Comparator.comparingInt(WeaknessField::score))
                .limit(2)
                .map(WeaknessField::name)
                .toList();
    }

    private static int safeInt(Float value) {
        if (value == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, Math.round(value)));
    }

    private String toJsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            log.warn("AI weekly plan JSON array conversion failed errorType={} message={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return "[]";
        }
    }

    private record WeaknessField(String name, int score) {
    }

    private record PlanVersionContext(
            Integer versionNo,
            Long parentPlanId,
            Optional<MajorWeeklyPlan> activePlan
    ) {
    }
}
