package com.jinroon.jobe.plan.repository;

import com.jinroon.jobe.plan.domain.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MajorWeeklyPlanItemRepository extends JpaRepository<MajorWeeklyPlanItem, Long> {

    List<MajorWeeklyPlanItem> findByWeeklyPlanIdOrderByWeekNoAsc(Long weeklyPlanId);
}
