package com.jinroon.jobe.diagnosis.repository;

import com.jinroon.jobe.diagnosis.domain.*;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TendencyEvalResultRepository extends JpaRepository<TendencyEvalResult, Long> {

    Optional<TendencyEvalResult> findByDiagnosisSessionId(Long diagnosisSessionId);
}
