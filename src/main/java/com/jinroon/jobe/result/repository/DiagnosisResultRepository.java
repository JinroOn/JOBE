package com.jinroon.jobe.result.repository;

import com.jinroon.jobe.result.domain.*;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisResultRepository extends JpaRepository<DiagnosisResult, Long> {

    List<DiagnosisResult> findByUserId(Long userId);

    Optional<DiagnosisResult> findByDiagnosisSessionId(Long diagnosisSessionId);

    Optional<DiagnosisResult> findByShareToken(String shareToken);
}
