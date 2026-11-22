package com.jinroon.jobe.domain.exploration.repository;

import com.jinroon.jobe.domain.exploration.entity.ExplorationIssuedQuestion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExplorationIssuedQuestionRepository extends JpaRepository<ExplorationIssuedQuestion, Long> {

    List<ExplorationIssuedQuestion> findBySessionIdOrderByOrdinalAsc(Long sessionId);

    Optional<ExplorationIssuedQuestion> findByIdAndSessionId(Long id, Long sessionId);

    /** 다음 노출 순서 계산용. 발급이 없으면 0. */
    @Query("select coalesce(max(q.ordinal), 0) from ExplorationIssuedQuestion q where q.sessionId = :sessionId")
    int findMaxOrdinal(@Param("sessionId") Long sessionId);
}
