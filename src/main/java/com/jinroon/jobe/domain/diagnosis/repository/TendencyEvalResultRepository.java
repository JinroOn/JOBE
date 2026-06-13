package com.jinroon.jobe.domain.diagnosis.repository;

import com.jinroon.jobe.domain.diagnosis.entity.*;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TendencyEvalResultRepository extends JpaRepository<TendencyEvalResult, Long> {

    Optional<TendencyEvalResult> findByDiagnosisSessionId(Long diagnosisSessionId);

    void deleteByDiagnosisSessionId(Long diagnosisSessionId);
}
