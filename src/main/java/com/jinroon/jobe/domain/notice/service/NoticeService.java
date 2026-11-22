package com.jinroon.jobe.domain.notice.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.domain.notice.dto.request.NoticeRequest;
import com.jinroon.jobe.domain.notice.entity.Notice;
import com.jinroon.jobe.domain.notice.enums.NoticeEnums.NoticeDisplayType;
import com.jinroon.jobe.domain.notice.repository.NoticeRepository;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공지 서비스.
 * 노출 범위: 관리자는 전체, 일반 사용자(비로그인 포함)는 게시 시작된 공지만 본다.
 * activeOnly=true면 누구든 현재 게시 기간 안의 공지만 본다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeRepository noticeRepository;

    public Page<Notice> findNotices(boolean activeOnly, boolean admin, Pageable pageable) {
        LocalDateTime now = LocalDateTime.now();
        if (activeOnly) {
            return noticeRepository.findByStartAtLessThanEqualAndEndAtGreaterThanEqual(now, now, pageable);
        }
        if (admin) {
            return noticeRepository.findAll(pageable);
        }
        // 예약(게시 시작 전) 공지는 숨기고, 기간이 끝난 공지는 지난 공지로 남겨 둔다.
        return noticeRepository.findByStartAtLessThanEqual(now, pageable);
    }

    public Notice getNotice(Long noticeId, boolean admin) {
        Notice notice = get(noticeRepository, noticeId, ErrorCode.NOTICE_NOT_FOUND);
        if (!admin && !notice.isPublished(LocalDateTime.now())) {
            // 예약 공지는 존재 여부도 드러내지 않는다.
            throw new CustomException(ErrorCode.NOTICE_NOT_FOUND);
        }
        return notice;
    }

    /**
     * 작성자는 요청 본문이 아니라 인증된 관리자 ID로 정한다.
     */
    @Transactional
    public Notice createNotice(NoticeRequest request, Long adminUserId) {
        if (isBlank(request.getTitle()) || isBlank(request.getContent())
                || request.getStartAt() == null || request.getEndAt() == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        NoticeDisplayType displayType = parseDisplayType(request.getDisplayType());
        if (displayType == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        Notice notice = Notice.create(
                adminUserId,
                request.getTitle(),
                request.getContent(),
                displayType,
                request.getStartAt(),
                request.getEndAt()
        );
        validateDisplayPeriod(notice);
        return noticeRepository.save(notice);
    }

    @Transactional
    public Notice updateNotice(Long noticeId, NoticeRequest request) {
        Notice notice = get(noticeRepository, noticeId, ErrorCode.NOTICE_NOT_FOUND);
        notice.update(
                request.getTitle(),
                request.getContent(),
                parseDisplayType(request.getDisplayType()),
                request.getStartAt(),
                request.getEndAt()
        );
        validateDisplayPeriod(notice);
        return notice;
    }

    @Transactional
    public void deleteNotice(Long noticeId) {
        noticeRepository.delete(get(noticeRepository, noticeId, ErrorCode.NOTICE_NOT_FOUND));
    }

    private void validateDisplayPeriod(Notice notice) {
        if (!notice.hasValidDisplayPeriod()) {
            throw new CustomException(ErrorCode.NOTICE_INVALID_PERIOD);
        }
    }

    private static NoticeDisplayType parseDisplayType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return NoticeDisplayType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
