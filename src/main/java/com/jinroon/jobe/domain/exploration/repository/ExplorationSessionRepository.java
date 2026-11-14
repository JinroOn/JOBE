package com.jinroon.jobe.domain.exploration.repository;

import com.jinroon.jobe.domain.exploration.entity.ExplorationSession;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationSessionStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExplorationSessionRepository extends JpaRepository<ExplorationSession, Long> {

    /**
     * 변경 요청(출제·답변·단계 이동·완료·폐기)용. 세션 행을 SELECT ... FOR UPDATE로 잠근다(D07 4절).
     * 같은 회차의 동시 변경은 먼저 커밋한 요청만 성공하고, 대기한 요청은 최신 revision을 보게 된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ExplorationSession s where s.id = :id")
    Optional<ExplorationSession> findByIdForUpdate(@Param("id") Long id);

    Optional<ExplorationSession> findFirstByUserIdAndStatus(Long userId, ExplorationSessionStatus status);

    List<ExplorationSession> findByUserIdOrderByStartedAtDesc(Long userId);
}
