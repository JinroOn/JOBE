package com.jinroon.jobe.domain.user.repository;

import com.jinroon.jobe.domain.user.entity.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserFavoriteRepository extends JpaRepository<UserFavorite, Long> {

    List<UserFavorite> findByUserId(Long userId);

    boolean existsByUserIdAndMajorId(Long userId, Long majorId);
}
