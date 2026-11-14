package com.jinroon.jobe.domain.exploration.repository;

import com.jinroon.jobe.domain.exploration.entity.ExplorationResult;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExplorationResultRepository extends JpaRepository<ExplorationResult, Long> {

    Optional<ExplorationResult> findBySessionId(Long sessionId);
}
