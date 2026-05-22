package com.jinroon.jobe.domain.result.repository;

import com.jinroon.jobe.domain.result.entity.*;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisResultRepository extends JpaRepository<DiagnosisResult, Long> {

    List<DiagnosisResult> findByUserId(Long userId);

    Optional<DiagnosisResult> findByDiagnosisSessionId(Long diagnosisSessionId);

    Optional<DiagnosisResult> findByShareToken(String shareToken);
}
