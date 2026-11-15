package com.jinroon.jobe.domain.user.repository;

import com.jinroon.jobe.domain.user.entity.*;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<Session, Long> {

    List<Session> findByUserId(Long userId);

    Optional<Session> findByRefreshToken(String refreshToken);

    void deleteByRefreshToken(String refreshToken);

    void deleteByUserId(Long userId);

    /**
     * 세션을 바로 지우고 지운 행 수를 돌려준다(0 또는 1).
     * 같은 refresh 토큰으로 동시에 갱신하면 먼저 지운 요청만 1을 받는다.
     * 엔티티 삭제(delete)는 커밋 시점에 낙관적 락 오류(500)가 나므로 갱신에서는 이 메서드를 쓴다.
     */
    @Modifying
    @Query("delete from Session s where s.id = :id")
    int deleteSessionById(@Param("id") Long id);
}
