package com.jinroon.jobe.domain.consultation.repository;

import com.jinroon.jobe.domain.consultation.entity.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsultationSessionRepository extends JpaRepository<ConsultationSession, Long> {

    List<ConsultationSession> findByUserId(Long userId);
}
