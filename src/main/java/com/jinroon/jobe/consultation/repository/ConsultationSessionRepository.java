package com.jinroon.jobe.consultation.repository;

import com.jinroon.jobe.consultation.domain.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsultationSessionRepository extends JpaRepository<ConsultationSession, Long> {

    List<ConsultationSession> findByUserId(Long userId);
}
