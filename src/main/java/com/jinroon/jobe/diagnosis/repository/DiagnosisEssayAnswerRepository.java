package com.jinroon.jobe.diagnosis.repository;

import com.jinroon.jobe.diagnosis.domain.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisEssayAnswerRepository extends JpaRepository<DiagnosisEssayAnswer, Long> {

    List<DiagnosisEssayAnswer> findByDiagnosisSessionId(Long diagnosisSessionId);
}
