package com.jinroon.jobe.notice.service;

import static com.jinroon.jobe.common.domain.EntityLookup.get;

import com.jinroon.jobe.common.domain.EntityFormMapper;
import com.jinroon.jobe.notice.domain.Notice;
import com.jinroon.jobe.notice.repository.NoticeRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeRepository noticeRepository;

    public List<Notice> findNotices(boolean activeOnly) {
        LocalDateTime now = LocalDateTime.now();
        return activeOnly
                ? noticeRepository.findByStartAtLessThanEqualAndEndAtGreaterThanEqual(now, now)
                : noticeRepository.findAll();
    }

    public Notice getNotice(Long noticeId) {
        return get(noticeRepository, noticeId, "Notice");
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
            throw new IllegalArgumentException("Notice endAt must be after or equal to startAt");
        }
    }
}
