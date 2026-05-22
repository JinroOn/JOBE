package com.jinroon.jobe.domain.diagnosis.repository;

import com.jinroon.jobe.domain.diagnosis.entity.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisExamAnswerRepository extends JpaRepository<DiagnosisExamAnswer, Long> {

    List<DiagnosisExamAnswer> findByDiagnosisSessionId(Long diagnosisSessionId);
}
