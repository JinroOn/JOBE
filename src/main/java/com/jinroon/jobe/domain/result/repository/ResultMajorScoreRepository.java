package com.jinroon.jobe.domain.result.repository;

import com.jinroon.jobe.domain.result.entity.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResultMajorScoreRepository extends JpaRepository<ResultMajorScore, Long> {

    List<ResultMajorScore> findByDiagnosisResultIdOrderByRankAsc(Long diagnosisResultId);

    void deleteAllByDiagnosisResultId(Long diagnosisResultId);
}
