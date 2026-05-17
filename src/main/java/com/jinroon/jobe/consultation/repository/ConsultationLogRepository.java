package com.jinroon.jobe.consultation.repository;

import com.jinroon.jobe.consultation.domain.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsultationLogRepository extends JpaRepository<ConsultationLog, Long> {

    List<ConsultationLog> findByConsultationSessionIdOrderByCreatedAtAsc(Long consultationSessionId);
}
