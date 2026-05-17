package com.jinroon.jobe.user.repository;

import com.jinroon.jobe.user.domain.*;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<Session, Long> {

    List<Session> findByUserId(Long userId);

    Optional<Session> findByRefreshToken(String refreshToken);

    void deleteByRefreshToken(String refreshToken);
}
