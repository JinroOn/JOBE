package com.jinroon.jobe.domain.diagnosis.repository;

import com.jinroon.jobe.domain.diagnosis.entity.*;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompetencyEvalResultRepository extends JpaRepository<CompetencyEvalResult, Long> {

    Optional<CompetencyEvalResult> findByDiagnosisSessionId(Long diagnosisSessionId);
}
