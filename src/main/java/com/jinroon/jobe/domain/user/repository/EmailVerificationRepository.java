package com.jinroon.jobe.domain.user.repository;

import com.jinroon.jobe.domain.user.entity.*;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    Optional<EmailVerification> findByEmailAndToken(String email, String token);

    Optional<EmailVerification> findFirstByEmailOrderByCreatedAtDesc(String email);

    Optional<EmailVerification> findFirstByEmailAndUsedTrueOrderByUpdatedAtDesc(String email);

    void deleteByEmailAndUsedFalse(String email);
}
