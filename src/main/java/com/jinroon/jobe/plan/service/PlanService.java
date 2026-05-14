package com.jinroon.jobe.plan.service;

import static com.jinroon.jobe.common.domain.EntityLookup.get;

import com.jinroon.jobe.common.domain.EntityFormMapper;
import com.jinroon.jobe.plan.domain.MajorWeeklyPlan;
import com.jinroon.jobe.plan.domain.MajorWeeklyPlanItem;
import com.jinroon.jobe.plan.domain.MajorWeeklyPlanRiskNote;
import com.jinroon.jobe.plan.repository.MajorWeeklyPlanItemRepository;
import com.jinroon.jobe.plan.repository.MajorWeeklyPlanRepository;
import com.jinroon.jobe.plan.repository.MajorWeeklyPlanRiskNoteRepository;
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
        return get(planRepository, planId, "MajorWeeklyPlan");
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
