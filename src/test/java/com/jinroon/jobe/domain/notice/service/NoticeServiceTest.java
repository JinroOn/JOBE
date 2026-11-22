package com.jinroon.jobe.domain.notice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jinroon.jobe.domain.notice.dto.request.NoticeRequest;
import com.jinroon.jobe.domain.notice.entity.Notice;
import com.jinroon.jobe.domain.notice.enums.NoticeEnums.NoticeDisplayType;
import com.jinroon.jobe.domain.notice.repository.NoticeRepository;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
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
 * 공지 서비스 테스트: 게시 기간 검증(1단계 기준선),
 * 일반 사용자 노출 범위 제한과 명시적 생성·수정(5단계).
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

    // ---- 목록 노출 범위 ----

    @Test
    void activeOnlyQueriesCurrentDisplayPeriodForAnyone() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Notice> page = new PageImpl<>(List.of());
        when(noticeRepository.findByStartAtLessThanEqualAndEndAtGreaterThanEqual(any(), any(), eq(pageable)))
                .thenReturn(page);

        assertThat(service.findNotices(true, false, pageable)).isSameAs(page);

        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(noticeRepository).findByStartAtLessThanEqualAndEndAtGreaterThanEqual(
                startCaptor.capture(), endCaptor.capture(), eq(pageable));
        assertThat(startCaptor.getValue()).isEqualTo(endCaptor.getValue());
        verify(noticeRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void adminSeesAllNotices() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Notice> page = new PageImpl<>(List.of());
        when(noticeRepository.findAll(pageable)).thenReturn(page);

        assertThat(service.findNotices(false, true, pageable)).isSameAs(page);
    }

    @Test
    void nonAdminSeesOnlyPublishedNotices() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Notice> page = new PageImpl<>(List.of());
        when(noticeRepository.findByStartAtLessThanEqual(any(), eq(pageable))).thenReturn(page);

        assertThat(service.findNotices(false, false, pageable)).isSameAs(page);
        verify(noticeRepository, never()).findAll(any(Pageable.class));
    }

    // ---- 단건 노출 범위 ----

    @Test
    void nonAdminCannotOpenScheduledNotice() {
        Notice scheduled = notice(LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(7));
        when(noticeRepository.findById(1L)).thenReturn(Optional.of(scheduled));

        assertErrorCode(() -> service.getNotice(1L, false), ErrorCode.NOTICE_NOT_FOUND);
    }

    @Test
    void nonAdminCanOpenEndedNotice() {
        Notice ended = notice(LocalDateTime.now().minusDays(7), LocalDateTime.now().minusDays(1));
        when(noticeRepository.findById(1L)).thenReturn(Optional.of(ended));

        assertThat(service.getNotice(1L, false)).isSameAs(ended);
    }

    @Test
    void adminCanOpenScheduledNotice() {
        Notice scheduled = notice(LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(7));
        when(noticeRepository.findById(1L)).thenReturn(Optional.of(scheduled));

        assertThat(service.getNotice(1L, true)).isSameAs(scheduled);
    }

    @Test
    void getNoticeRejectsMissingNotice() {
        when(noticeRepository.findById(1L)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.getNotice(1L, true), ErrorCode.NOTICE_NOT_FOUND);
    }

    // ---- 생성·수정 ----

    @Test
    void createNoticeUsesAuthenticatedAdminAsAuthor() {
        when(noticeRepository.save(any(Notice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 0, 0);
        NoticeRequest request = request(start, start.plusDays(7));
        request.setCreatedBy(999L);

        Notice notice = service.createNotice(request, 1L);

        assertThat(notice.getCreatedBy()).isEqualTo(1L);
        assertThat(notice.getTitle()).isEqualTo("점검 안내");
        assertThat(notice.getDisplayType()).isEqualTo(NoticeDisplayType.banner);
        assertThat(notice.getStartAt()).isEqualTo(start);
    }

    @Test
    void createNoticeRejectsEndBeforeStart() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 8, 0, 0);

        assertErrorCode(() -> service.createNotice(request(start, start.minusDays(1)), 1L),
                ErrorCode.NOTICE_INVALID_PERIOD);
        verify(noticeRepository, never()).save(any());
    }

    @Test
    void createNoticeRejectsMissingFields() {
        NoticeRequest request = request(LocalDateTime.now(), LocalDateTime.now().plusDays(1));
        request.setTitle(null);

        assertErrorCode(() -> service.createNotice(request, 1L), ErrorCode.INVALID_INPUT);
    }

    @Test
    void createNoticeRejectsUnknownDisplayType() {
        NoticeRequest request = request(LocalDateTime.now(), LocalDateTime.now().plusDays(1));
        request.setDisplayType("toast");

        assertErrorCode(() -> service.createNotice(request, 1L), ErrorCode.INVALID_INPUT);
    }

    @Test
    void updateNoticeChangesOnlyGivenFieldsAndKeepsAuthor() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 0, 0);
        Notice existing = Notice.create(1L, "점검 안내", "내용", NoticeDisplayType.banner, start, start.plusDays(7));
        when(noticeRepository.findById(1L)).thenReturn(Optional.of(existing));
        NoticeRequest update = new NoticeRequest();
        update.setTitle("점검 일정 변경");
        update.setCreatedBy(999L);

        service.updateNotice(1L, update);

        assertThat(existing.getTitle()).isEqualTo("점검 일정 변경");
        assertThat(existing.getContent()).isEqualTo("내용");
        assertThat(existing.getCreatedBy()).isEqualTo(1L);
    }

    @Test
    void updateNoticeRejectsEndBeforeStart() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 1, 0, 0);
        Notice existing = Notice.create(1L, "점검 안내", "내용", NoticeDisplayType.banner, start, start.plusDays(7));
        when(noticeRepository.findById(1L)).thenReturn(Optional.of(existing));
        NoticeRequest update = new NoticeRequest();
        update.setEndAt(start.minusDays(1));

        assertErrorCode(() -> service.updateNotice(1L, update), ErrorCode.NOTICE_INVALID_PERIOD);
    }

    private void assertErrorCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(expected);
    }

    private Notice notice(LocalDateTime startAt, LocalDateTime endAt) {
        return Notice.create(1L, "공지", "내용", NoticeDisplayType.banner, startAt, endAt);
    }

    private NoticeRequest request(LocalDateTime startAt, LocalDateTime endAt) {
        NoticeRequest request = new NoticeRequest();
        request.setTitle("점검 안내");
        request.setContent("서버 점검이 예정되어 있습니다.");
        request.setDisplayType("banner");
        request.setStartAt(startAt);
        request.setEndAt(endAt);
        return request;
    }
}
