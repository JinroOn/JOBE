package com.jinroon.jobe.domain.diagnosis.repository;

import com.jinroon.jobe.domain.diagnosis.entity.*;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiagnosisExamAnswerRepository extends JpaRepository<DiagnosisExamAnswer, Long> {

    List<DiagnosisExamAnswer> findByDiagnosisSessionId(Long diagnosisSessionId);

    Optional<DiagnosisExamAnswer> findByDiagnosisSessionIdAndExamQuestionId(Long diagnosisSessionId, Long examQuestionId);

    void deleteAllByDiagnosisSessionId(Long diagnosisSessionId);
}
