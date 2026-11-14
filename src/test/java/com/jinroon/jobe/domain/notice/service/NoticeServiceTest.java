package com.jinroon.jobe.domain.notice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jinroon.jobe.domain.notice.entity.Notice;
import com.jinroon.jobe.domain.notice.repository.NoticeRepository;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * 3.1 이식 전 기준선 테스트: 공지 게시 기간과 CRUD 검증.
 * 비로그인 사용자의 activeOnly 강제는 5단계에서 컨트롤러에 추가하므로 여기서는 서비스 동작만 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class NoticeServiceTest {

    @Mock
    private NoticeRepository noticeRepository;

    private NoticeService service;

    @BeforeEach
    void setUp() {
        service = new NoticeService(noticeRepository);
    }

    @Test
    void findNoticesActiveOnlyQueriesCurrentDisplayPeriod() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Notice> page = new PageImpl<>(List.of());
        when(noticeRepository.findByStartAtLessThanEqualAndEndAtGreaterThanEqual(any(), any(), eq(pageable)))
                .thenReturn(page);

        Page<Notice> result = service.findNotices(true, pageable);

        assertThat(result).isSameAs(page);
        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(noticeRepository).findByStartAtLessThanEqualAndEndAtGreaterThanEqual(
                startCaptor.capture(), endCaptor.capture(), eq(pageable));
        assertThat(startCaptor.getValue()).isEqualTo(endCaptor.getValue());
        verify(noticeRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void findNoticesWithoutActiveOnlyReturnsAll() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Notice> page = new PageImpl<>(List.of());
        when(noticeRepository.findAll(pageable)).thenReturn(page);

        assertThat(service.findNotices(false, pageable)).isSameAs(page);
    }

    @Test
    void createNoticeSavesValidPeriod() {
        when(noticeRepository.save(any(Notice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 0, 0);

        Notice notice = service.createNotice(noticeValues(start, start.plusDays(7)));

        assertThat(notice.getTitle()).isEqualTo("점검 안내");
        assertThat(notice.getStartAt()).isEqualTo(start);
    }

    @Test
    void createNoticeRejectsEndBeforeStart() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 8, 0, 0);

        assertErrorCode(() -> service.createNotice(noticeValues(start, start.minusDays(1))),
                ErrorCode.NOTICE_INVALID_PERIOD);
        verify(noticeRepository, never()).save(any());
    }

    @Test
    void updateNoticeRejectsEndBeforeStart() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 0, 0);
        when(noticeRepository.save(any(Notice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Notice existing = service.createNotice(noticeValues(start, start.plusDays(7)));
        when(noticeRepository.findById(1L)).thenReturn(Optional.of(existing));

        Map<String, Object> update = new HashMap<>();
        update.put("endAt", start.minusDays(1));

        assertErrorCode(() -> service.updateNotice(1L, update), ErrorCode.NOTICE_INVALID_PERIOD);
    }

    @Test
    void getNoticeRejectsMissingNotice() {
        when(noticeRepository.findById(1L)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.getNotice(1L), ErrorCode.NOTICE_NOT_FOUND);
    }

    private void assertErrorCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(expected);
    }

    private Map<String, Object> noticeValues(LocalDateTime startAt, LocalDateTime endAt) {
        Map<String, Object> values = new HashMap<>();
        values.put("createdBy", 1L);
        values.put("title", "점검 안내");
        values.put("content", "서버 점검이 예정되어 있습니다.");
        values.put("displayType", "banner");
        values.put("startAt", startAt);
        values.put("endAt", endAt);
        return values;
    }
}
