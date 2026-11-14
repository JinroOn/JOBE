package com.jinroon.jobe.domain.exploration.repository;

import com.jinroon.jobe.domain.exploration.entity.ExplorationIdempotency;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExplorationIdempotencyRepository extends JpaRepository<ExplorationIdempotency, Long> {

    Optional<ExplorationIdempotency> findByUserIdAndOperationAndResourceIdAndIdempotencyKey(
            Long userId, String operation, Long resourceId, String idempotencyKey);
}
