package com.jinroon.jobe.domain.plan.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.client.AiServiceClient;
import com.jinroon.jobe.global.client.dto.request.WeeklyPlanRequest;
import com.jinroon.jobe.global.client.dto.request.WeeklyPlanRequest.Constraints;
import com.jinroon.jobe.global.client.dto.request.WeeklyPlanRequest.TargetMajor;
import com.jinroon.jobe.global.client.dto.response.WeeklyPlanResponse;
import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.domain.diagnosis.entity.CompetencyEvalResult;
import com.jinroon.jobe.domain.diagnosis.repository.CompetencyEvalResultRepository;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

    public MajorWeeklyPlan getPlan(Long planId) {
        return get(planRepository, planId, ErrorCode.PLAN_NOT_FOUND);
    }

    public List<MajorWeeklyPlan> findPlansByResult(Long resultId) {
        return planRepository.findByDiagnosisResultId(resultId);
    }

    public List<MajorWeeklyPlanItem> findItems(Long planId) {
        return planItemRepository.findByWeeklyPlanIdOrderByWeekNoAsc(planId);
    }

    public List<MajorWeeklyPlanRiskNote> findRiskNotes(Long planId) {
        return riskNoteRepository.findByWeeklyPlanId(planId);
    }

    @Transactional
    public MajorWeeklyPlan createPlan(Map<String, Object> values) {
        MajorWeeklyPlan plan = planRepository.save(EntityFormMapper.create(MajorWeeklyPlan.class, values));

        try {
            applyAiWeeklyPlan(plan);
        } catch (Exception e) {
            log.warn("AI 주간 계획 적용 실패 (planId={}): {}", plan.getId(), e.getMessage());
        }

        return plan;
    }

    @Transactional
    public MajorWeeklyPlan updatePlan(Long planId, Map<String, Object> values) {
        MajorWeeklyPlan plan = getPlan(planId);
        EntityFormMapper.apply(plan, values);
        return plan;
    }

    @Transactional
    public MajorWeeklyPlanItem createItem(Map<String, Object> values) {
        return planItemRepository.save(EntityFormMapper.create(MajorWeeklyPlanItem.class, values));
    }

    @Transactional
    public MajorWeeklyPlanRiskNote createRiskNote(Map<String, Object> values) {
        return riskNoteRepository.save(EntityFormMapper.create(MajorWeeklyPlanRiskNote.class, values));
    }

    @Transactional
    public void completeItem(Long itemId) {
        MajorWeeklyPlanItem item = get(planItemRepository, itemId, ErrorCode.PLAN_ITEM_NOT_FOUND);
        item.complete();
    }

    private void applyAiWeeklyPlan(MajorWeeklyPlan plan) {
        Optional<DiagnosisResult> resultOpt =
                diagnosisResultRepository.findById(plan.getDiagnosisResultId());
        if (resultOpt.isEmpty()) {
            return;
        }
        DiagnosisResult result = resultOpt.get();

        Optional<CompetencyEvalResult> competencyOpt =
                competencyEvalResultRepository.findByDiagnosisSessionId(result.getDiagnosisSessionId());
        if (competencyOpt.isEmpty()) {
            return;
        }
        CompetencyEvalResult competency = competencyOpt.get();

        Optional<ResultMajorScore> scoreOpt =
                resultMajorScoreRepository.findById(plan.getResultMajorScoreId());
        if (scoreOpt.isEmpty()) {
            return;
        }
        ResultMajorScore score = scoreOpt.get();

        Optional<Major> majorOpt = majorRepository.findById(score.getMajorId());
        if (majorOpt.isEmpty()) {
            return;
        }
        String majorName = majorOpt.get().getName();

        Map<String, Double> profile = Map.of(
                "math_logic", competency.getMathLogic().doubleValue(),
                "problem_solving", competency.getProblemSolving().doubleValue(),
                "info_tech", competency.getInfoTech().doubleValue(),
                "implementation", competency.getImplementation().doubleValue(),
                "system_understanding", competency.getSystemUnderstanding().doubleValue(),
                "data_analysis", competency.getDataAnalysis().doubleValue(),
                "communication", competency.getCommunication().doubleValue(),
                "collaboration", competency.getCollaboration().doubleValue(),
                "self_management", competency.getSelfManagement().doubleValue()
        );

        List<String> weaknessFocus = (result.getWeaknessFocus() != null && !result.getWeaknessFocus().isBlank())
                ? Arrays.asList(result.getWeaknessFocus().split(","))
                : List.of();

        WeeklyPlanRequest request = new WeeklyPlanRequest(
                result.getDiagnosisSessionId(),
                new TargetMajor(majorName, null),
                weaknessFocus,
                profile,
                new Constraints(12, null)
        );

        WeeklyPlanResponse response = aiServiceClient.getWeeklyPlan(request);
        if (response == null) {
            return;
        }

        plan.applyAiPlan(response.planId(), response.overview());

        List<MajorWeeklyPlanItem> items = response.weeklyPlan().stream()
                .map(wp -> MajorWeeklyPlanItem.of(
                        plan.getId(),
                        wp.week(),
                        wp.goal(),
                        wp.tasks() != null ? String.join(",", wp.tasks()) : null,
                        wp.recommendedResources() != null ? String.join(",", wp.recommendedResources()) : null,
                        wp.checkpoint()
                ))
                .collect(Collectors.toList());
        planItemRepository.saveAll(items);

        if (response.riskNotes() != null && !response.riskNotes().isBlank()) {
            riskNoteRepository.save(MajorWeeklyPlanRiskNote.of(plan.getId(), response.riskNotes()));
        }
    }
}
