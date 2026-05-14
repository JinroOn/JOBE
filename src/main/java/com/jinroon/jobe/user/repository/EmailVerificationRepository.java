package com.jinroon.jobe.user.repository;

import com.jinroon.jobe.user.domain.*;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    Optional<EmailVerification> findByToken(String token);

    Optional<EmailVerification> findFirstByEmailOrderByCreatedAtDesc(String email);
}
