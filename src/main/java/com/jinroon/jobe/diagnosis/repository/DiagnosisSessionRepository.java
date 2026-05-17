package com.jinroon.jobe.diagnosis.repository;

import com.jinroon.jobe.diagnosis.domain.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisSessionRepository extends JpaRepository<DiagnosisSession, Long> {

    List<DiagnosisSession> findByUserId(Long userId);
}
