package com.jinroon.jobe.domain.diagnosis.repository;

import com.jinroon.jobe.domain.diagnosis.entity.*;
import com.jinroon.jobe.domain.diagnosis.enums.DiagnosisEnums.DiagnosisStatus;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisSessionRepository extends JpaRepository<DiagnosisSession, Long> {

    List<DiagnosisSession> findByUserId(Long userId);

    Optional<DiagnosisSession> findByUserIdAndStatus(Long userId, DiagnosisStatus status);

    Optional<DiagnosisSession> findFirstByUserIdAndStatusOrderByIdDesc(Long userId, DiagnosisStatus status);
}
