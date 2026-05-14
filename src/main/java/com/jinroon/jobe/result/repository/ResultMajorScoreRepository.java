package com.jinroon.jobe.result.repository;

import com.jinroon.jobe.result.domain.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResultMajorScoreRepository extends JpaRepository<ResultMajorScore, Long> {

    List<ResultMajorScore> findByDiagnosisResultIdOrderByRankAsc(Long diagnosisResultId);
}
