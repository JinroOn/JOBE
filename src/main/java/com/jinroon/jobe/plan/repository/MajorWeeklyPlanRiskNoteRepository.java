package com.jinroon.jobe.plan.repository;

import com.jinroon.jobe.plan.domain.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MajorWeeklyPlanRiskNoteRepository extends JpaRepository<MajorWeeklyPlanRiskNote, Long> {

    List<MajorWeeklyPlanRiskNote> findByWeeklyPlanId(Long weeklyPlanId);
}
