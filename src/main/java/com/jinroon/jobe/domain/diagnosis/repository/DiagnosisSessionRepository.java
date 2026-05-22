package com.jinroon.jobe.domain.diagnosis.repository;

import com.jinroon.jobe.domain.diagnosis.entity.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisSessionRepository extends JpaRepository<DiagnosisSession, Long> {

    List<DiagnosisSession> findByUserId(Long userId);
}
