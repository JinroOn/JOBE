package com.jinroon.jobe.domain.plan.repository;

import com.jinroon.jobe.domain.plan.entity.*;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MajorWeeklyPlanRepository extends JpaRepository<MajorWeeklyPlan, Long> {

    List<MajorWeeklyPlan> findByDiagnosisResultId(Long diagnosisResultId);

    List<MajorWeeklyPlan> findByDiagnosisResultIdAndActiveVersionTrue(Long diagnosisResultId);

    Optional<MajorWeeklyPlan> findByResultMajorScoreIdAndActiveVersionTrue(Long resultMajorScoreId);

    Optional<MajorWeeklyPlan> findTopByResultMajorScoreIdOrderByVersionNoDesc(Long resultMajorScoreId);
}
