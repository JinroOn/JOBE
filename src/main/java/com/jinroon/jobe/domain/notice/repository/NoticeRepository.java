package com.jinroon.jobe.domain.notice.repository;

import com.jinroon.jobe.domain.notice.entity.*;

import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    Page<Notice> findByStartAtLessThanEqualAndEndAtGreaterThanEqual(LocalDateTime startAt, LocalDateTime endAt, Pageable pageable);
}
