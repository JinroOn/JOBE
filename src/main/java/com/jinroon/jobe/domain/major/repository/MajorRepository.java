package com.jinroon.jobe.domain.major.repository;

import com.jinroon.jobe.domain.major.entity.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MajorRepository extends JpaRepository<Major, Long> {

    List<Major> findByCategory(String category);

    List<Major> findByNameContaining(String keyword);
}
