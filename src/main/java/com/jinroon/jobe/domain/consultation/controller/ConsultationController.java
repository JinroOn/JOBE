package com.jinroon.jobe.domain.consultation.controller;

import com.jinroon.jobe.domain.consultation.controller.api.ConsultationApi;
import com.jinroon.jobe.domain.consultation.entity.ConsultationLog;
import com.jinroon.jobe.domain.consultation.entity.ConsultationSession;
import com.jinroon.jobe.domain.consultation.service.ConsultationService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
    public List<ConsultationSession> findSessionsByUser(@PathVariable Long userId) {
        return consultationService.findSessionsByUser(userId);
    }

    @Override
    @GetMapping("/sessions/{sessionId}")
    public ConsultationSession getSession(@PathVariable Long sessionId) {
        return consultationService.getSession(sessionId);
    }

    @Override
    @GetMapping("/sessions/{sessionId}/logs")
    public List<ConsultationLog> findLogs(@PathVariable Long sessionId) {
        return consultationService.findLogs(sessionId);
    }

    @Override
    @GetMapping("/logs/{logId}")
    public ConsultationLog getLog(@PathVariable Long logId) {
        return consultationService.getLog(logId);
    }

    @Override
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ConsultationSession createSession(@RequestBody Map<String, Object> request) {
        return consultationService.createSession(request);
    }

    @Override
    @PatchMapping("/sessions/{sessionId}")
    public ConsultationSession updateSession(@PathVariable Long sessionId, @RequestBody Map<String, Object> request) {
        return consultationService.updateSession(sessionId, request);
    }

    @Override
    @PostMapping("/sessions/{sessionId}/end")
    public ConsultationSession endSession(@PathVariable Long sessionId) {
        return consultationService.endSession(sessionId);
    }

    @Override
    @PostMapping("/logs")
    @ResponseStatus(HttpStatus.CREATED)
    public ConsultationLog createLog(@RequestBody Map<String, Object> request) {
        return consultationService.createLog(request);
    }
}
