package com.jinroon.jobe.domain.consultation.repository;

import com.jinroon.jobe.domain.consultation.entity.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsultationLogRepository extends JpaRepository<ConsultationLog, Long> {

    List<ConsultationLog> findByConsultationSessionIdOrderByCreatedAtAsc(Long consultationSessionId);

    void deleteAllByConsultationSessionIdIn(List<Long> consultationSessionIds);
}
