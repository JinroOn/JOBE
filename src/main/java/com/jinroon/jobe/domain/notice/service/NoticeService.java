package com.jinroon.jobe.domain.notice.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.domain.notice.entity.Notice;
import com.jinroon.jobe.domain.notice.repository.NoticeRepository;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeRepository noticeRepository;

    public Page<Notice> findNotices(boolean activeOnly, Pageable pageable) {
        LocalDateTime now = LocalDateTime.now();
        return activeOnly
                ? noticeRepository.findByStartAtLessThanEqualAndEndAtGreaterThanEqual(now, now, pageable)
                : noticeRepository.findAll(pageable);
    }

    public Notice getNotice(Long noticeId) {
        return get(noticeRepository, noticeId, ErrorCode.NOTICE_NOT_FOUND);
    }

    @Transactional
    public Notice createNotice(Map<String, Object> values) {
        Notice notice = EntityFormMapper.create(Notice.class, values);
        validateDisplayPeriod(notice);
        return noticeRepository.save(notice);
    }

    @Transactional
    public Notice updateNotice(Long noticeId, Map<String, Object> values) {
        Notice notice = getNotice(noticeId);
        EntityFormMapper.apply(notice, values);
        validateDisplayPeriod(notice);
        return notice;
    }

    @Transactional
    public void deleteNotice(Long noticeId) {
        noticeRepository.delete(getNotice(noticeId));
    }

    private void validateDisplayPeriod(Notice notice) {
        if (!notice.hasValidDisplayPeriod()) {
            throw new CustomException(ErrorCode.NOTICE_INVALID_PERIOD);
        }
    }
}
