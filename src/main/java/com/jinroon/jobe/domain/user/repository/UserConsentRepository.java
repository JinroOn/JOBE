package com.jinroon.jobe.domain.user.repository;

import com.jinroon.jobe.domain.user.entity.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

    List<UserConsent> findByUserIdOrderByAgreedAtDescIdDesc(Long userId);
    java.util.Optional<UserConsent> findFirstByUserIdOrderByAgreedAtDescIdDesc(Long userId);

    List<UserConsent> findByUserId(Long userId);
}
