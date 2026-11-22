package com.jinroon.jobe.domain.plan.repository;

import com.jinroon.jobe.domain.plan.entity.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MajorWeeklyPlanRiskNoteRepository extends JpaRepository<MajorWeeklyPlanRiskNote, Long> {

    List<MajorWeeklyPlanRiskNote> findByWeeklyPlanId(Long weeklyPlanId);

    void deleteAllByWeeklyPlanIdIn(List<Long> weeklyPlanIds);
}
