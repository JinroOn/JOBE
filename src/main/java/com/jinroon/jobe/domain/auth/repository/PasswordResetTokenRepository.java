package com.jinroon.jobe.domain.auth.repository;

import com.jinroon.jobe.domain.auth.entity.PasswordResetToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByEmailAndToken(String email, String token);

    boolean existsByToken(String token);

    void deleteByEmailAndUsedFalse(String email);
}
