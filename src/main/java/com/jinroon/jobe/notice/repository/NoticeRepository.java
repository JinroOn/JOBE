package com.jinroon.jobe.notice.repository;

import com.jinroon.jobe.notice.domain.*;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    List<Notice> findByStartAtLessThanEqualAndEndAtGreaterThanEqual(LocalDateTime startAt, LocalDateTime endAt);
}
