package com.jinroon.jobe.domain.diagnosis.repository;

import com.jinroon.jobe.domain.diagnosis.entity.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisEssayAnswerRepository extends JpaRepository<DiagnosisEssayAnswer, Long> {

    List<DiagnosisEssayAnswer> findByDiagnosisSessionId(Long diagnosisSessionId);

    void deleteAllByDiagnosisSessionId(Long diagnosisSessionId);
}
