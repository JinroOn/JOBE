package com.jinroon.jobe.major.repository;

import com.jinroon.jobe.major.domain.*;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MajorRepository extends JpaRepository<Major, Long> {

    List<Major> findByCategory(String category);

    List<Major> findByNameContaining(String keyword);
}
