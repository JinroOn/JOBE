package com.jinroon.jobe.domain.major.repository;

import com.jinroon.jobe.domain.major.entity.*;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MajorRepository extends JpaRepository<Major, Long> {

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select m from Major m where m.id = :id")
    Optional<Major> findByIdForReference(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select m from Major m order by m.id")
    List<Major> findAllForReference();

    List<Major> findByCategory(String category);

    List<Major> findByNameContaining(String keyword);
}
