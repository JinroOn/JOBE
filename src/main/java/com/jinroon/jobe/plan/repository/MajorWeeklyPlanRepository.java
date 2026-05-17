package com.jinroon.jobe.plan.repository;

import com.jinroon.jobe.plan.domain.*;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MajorWeeklyPlanRepository extends JpaRepository<MajorWeeklyPlan, Long> {

    List<MajorWeeklyPlan> findByDiagnosisResultId(Long diagnosisResultId);

    Optional<MajorWeeklyPlan> findByResultMajorScoreIdAndActiveVersionTrue(Long resultMajorScoreId);
}
