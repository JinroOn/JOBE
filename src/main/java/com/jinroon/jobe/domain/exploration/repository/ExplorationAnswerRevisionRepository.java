package com.jinroon.jobe.domain.exploration.repository;

import com.jinroon.jobe.domain.exploration.entity.ExplorationAnswerRevision;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExplorationAnswerRevisionRepository extends JpaRepository<ExplorationAnswerRevision, Long> {

    Optional<ExplorationAnswerRevision> findByIssuedIdAndCurrentTrue(Long issuedId);

    Optional<ExplorationAnswerRevision> findByIssuedIdAndClientMutationId(Long issuedId, String clientMutationId);

    Optional<ExplorationAnswerRevision> findFirstByIssuedIdOrderByAnswerRevisionDesc(Long issuedId);

    /** 이어하기·계산용: 여러 발급 문항의 최신 답변. */
    List<ExplorationAnswerRevision> findByIssuedIdInAndCurrentTrue(Collection<Long> issuedIds);
}
