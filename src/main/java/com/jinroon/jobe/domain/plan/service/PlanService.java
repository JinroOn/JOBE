package com.jinroon.jobe.domain.plan.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlan;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlanItem;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlanRiskNote;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanItemRepository;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanRepository;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanRiskNoteRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PlanService {

    private final MajorWeeklyPlanRepository planRepository;
    private final MajorWeeklyPlanItemRepository planItemRepository;
    private final MajorWeeklyPlanRiskNoteRepository riskNoteRepository;

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
        return planRepository.save(EntityFormMapper.create(MajorWeeklyPlan.class, values));
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
}
