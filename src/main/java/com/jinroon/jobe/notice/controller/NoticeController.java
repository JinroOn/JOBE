package com.jinroon.jobe.notice.controller;

import com.jinroon.jobe.notice.domain.Notice;
import com.jinroon.jobe.notice.service.NoticeService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
public class NoticeController {

    private final NoticeService noticeService;

    @GetMapping
    public List<Notice> findNotices(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return noticeService.findNotices(activeOnly);
    }

    @GetMapping("/{noticeId}")
    public Notice getNotice(@PathVariable Long noticeId) {
        return noticeService.getNotice(noticeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Notice createNotice(@RequestBody Map<String, Object> request) {
        return noticeService.createNotice(request);
    }

    @PatchMapping("/{noticeId}")
    public Notice updateNotice(@PathVariable Long noticeId, @RequestBody Map<String, Object> request) {
        return noticeService.updateNotice(noticeId, request);
    }

    @DeleteMapping("/{noticeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNotice(@PathVariable Long noticeId) {
        noticeService.deleteNotice(noticeId);
    }
}
