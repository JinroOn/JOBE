package com.jinroon.jobe.domain.plan.repository;

import com.jinroon.jobe.domain.plan.entity.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MajorWeeklyPlanItemRepository extends JpaRepository<MajorWeeklyPlanItem, Long> {

    List<MajorWeeklyPlanItem> findByWeeklyPlanIdOrderByWeekNoAsc(Long weeklyPlanId);

    void deleteAllByWeeklyPlanIdIn(List<Long> weeklyPlanIds);
}
