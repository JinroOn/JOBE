package com.jinroon.jobe.domain.notice.controller;

import com.jinroon.jobe.domain.notice.controller.api.NoticeApi;
import com.jinroon.jobe.domain.notice.dto.request.NoticeRequest;
import com.jinroon.jobe.domain.notice.entity.Notice;
import com.jinroon.jobe.domain.notice.service.NoticeService;
import com.jinroon.jobe.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notices")
public class NoticeController implements NoticeApi {

    private final NoticeService noticeService;

    @Override
    @GetMapping
    public Page<Notice> findNotices(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @PageableDefault(size = 10, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return noticeService.findNotices(activeOnly, isAdmin(userDetails), pageable);
    }

    @Override
    @GetMapping("/{noticeId}")
    public Notice getNotice(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long noticeId) {
        return noticeService.getNotice(noticeId, isAdmin(userDetails));
    }

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Notice createNotice(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody NoticeRequest request) {
        // SecurityConfig에서 관리자만 통과하므로 userDetails는 항상 있다.
        return noticeService.createNotice(request, userDetails.getUserId());
    }

    @Override
    @PatchMapping("/{noticeId}")
    public Notice updateNotice(@PathVariable Long noticeId, @Valid @RequestBody NoticeRequest request) {
        return noticeService.updateNotice(noticeId, request);
    }

    @Override
    @DeleteMapping("/{noticeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNotice(@PathVariable Long noticeId) {
        noticeService.deleteNotice(noticeId);
    }

    private static boolean isAdmin(CustomUserDetails userDetails) {
        return userDetails != null && "admin".equals(userDetails.getUser().getRoleName());
    }
}
