package com.jinroon.jobe.domain.consultation.controller;

import com.jinroon.jobe.domain.consultation.controller.api.ConsultationApi;
import com.jinroon.jobe.domain.consultation.entity.ConsultationLog;
import com.jinroon.jobe.domain.consultation.entity.ConsultationSession;
import com.jinroon.jobe.domain.consultation.service.ConsultationService;
import com.jinroon.jobe.global.security.CustomUserDetails;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/consultations")
public class ConsultationController implements ConsultationApi {

    private final ConsultationService consultationService;

    @Override
    @GetMapping("/users/{userId}/sessions")
    public List<ConsultationSession> findSessionsByUser(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long userId) {
        return consultationService.findSessionsForUser(userId, userDetails.getUserId());
    }

    @Override
    @GetMapping("/sessions/{sessionId}")
    public ConsultationSession getSession(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long sessionId) {
        return consultationService.getSessionForUser(sessionId, userDetails.getUserId());
    }

    @Override
    @GetMapping("/sessions/{sessionId}/logs")
    public List<ConsultationLog> findLogs(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long sessionId) {
        return consultationService.findLogsForUser(sessionId, userDetails.getUserId());
    }

    @Override
    @GetMapping("/logs/{logId}")
    public ConsultationLog getLog(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long logId) {
        return consultationService.getLogForUser(logId, userDetails.getUserId());
    }

    @Override
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ConsultationSession createSession(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        return consultationService.createSessionForUser(request, userDetails.getUserId());
    }

    @Override
    @PatchMapping("/sessions/{sessionId}")
    public ConsultationSession updateSession(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long sessionId,
            @RequestBody Map<String, Object> request) {
        return consultationService.updateSessionForUser(sessionId, userDetails.getUserId(), request);
    }

    @Override
    @PostMapping("/sessions/{sessionId}/end")
    public ConsultationSession endSession(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long sessionId) {
        return consultationService.endSessionForUser(sessionId, userDetails.getUserId());
    }

    @Override
    @PostMapping("/logs")
    @ResponseStatus(HttpStatus.CREATED)
    public ConsultationLog createLog(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        return consultationService.createLogForUser(request, userDetails.getUserId());
    }
}
