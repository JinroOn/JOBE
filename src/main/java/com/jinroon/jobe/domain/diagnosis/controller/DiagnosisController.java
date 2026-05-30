package com.jinroon.jobe.domain.diagnosis.controller;

import com.jinroon.jobe.domain.diagnosis.controller.api.DiagnosisApi;
import com.jinroon.jobe.domain.diagnosis.entity.*;
import com.jinroon.jobe.domain.diagnosis.service.DiagnosisService;
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
@RequestMapping("/api/diagnoses")
public class DiagnosisController implements DiagnosisApi {

    private final DiagnosisService diagnosisService;

    @Override
    @GetMapping("/sessions/{sessionId}")
    public DiagnosisSession getSession(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long sessionId) {
        return diagnosisService.getSessionForUser(sessionId, userDetails.getUserId());
    }

    @Override
    @GetMapping("/users/{userId}/sessions")
    public List<DiagnosisSession> findSessionsByUser(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long userId) {
        return diagnosisService.findSessionsForUser(userId, userDetails.getUserId());
    }

    @Override
    @GetMapping("/questions")
    public List<ExamQuestion> findQuestions() {
        return diagnosisService.findQuestions();
    }

    @Override
    @GetMapping("/questions/{questionId}")
    public ExamQuestion getQuestion(@PathVariable Long questionId) {
        return diagnosisService.getQuestion(questionId);
    }

    @Override
    @GetMapping("/sessions/{sessionId}/exam-answers")
    public List<DiagnosisExamAnswer> findExamAnswers(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long sessionId) {
        return diagnosisService.findExamAnswersForUser(sessionId, userDetails.getUserId());
    }

    @Override
    @GetMapping("/sessions/{sessionId}/essay-answers")
    public List<DiagnosisEssayAnswer> findEssayAnswers(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long sessionId) {
        return diagnosisService.findEssayAnswersForUser(sessionId, userDetails.getUserId());
    }

    @Override
    @GetMapping("/sessions/{sessionId}/competency-result")
    public CompetencyEvalResult getCompetencyResult(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long sessionId) {
        return diagnosisService.getCompetencyResultForUser(sessionId, userDetails.getUserId());
    }

    @Override
    @GetMapping("/sessions/{sessionId}/tendency-result")
    public TendencyEvalResult getTendencyResult(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long sessionId) {
        return diagnosisService.getTendencyResultForUser(sessionId, userDetails.getUserId());
    }

    @Override
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public DiagnosisSession createSession(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        return diagnosisService.createSessionForUser(request, userDetails.getUserId());
    }

    @Override
    @PatchMapping("/sessions/{sessionId}")
    public DiagnosisSession updateSession(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long sessionId,
            @RequestBody Map<String, Object> request) {
        return diagnosisService.updateSessionForUser(sessionId, userDetails.getUserId(), request);
    }

    @Override
    @PostMapping("/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public ExamQuestion createQuestion(@RequestBody Map<String, Object> request) {
        return diagnosisService.createQuestion(request);
    }

    @Override
    @PostMapping("/exam-answers")
    @ResponseStatus(HttpStatus.CREATED)
    public DiagnosisExamAnswer createExamAnswer(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        return diagnosisService.createExamAnswerForUser(request, userDetails.getUserId());
    }

    @Override
    @PostMapping("/essay-answers")
    @ResponseStatus(HttpStatus.CREATED)
    public DiagnosisEssayAnswer createEssayAnswer(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        return diagnosisService.createEssayAnswerForUser(request, userDetails.getUserId());
    }

    @Override
    @PostMapping("/competency-results")
    @ResponseStatus(HttpStatus.CREATED)
    public CompetencyEvalResult createCompetencyResult(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        return diagnosisService.createCompetencyResultForUser(request, userDetails.getUserId());
    }

    @Override
    @PostMapping("/tendency-results")
    @ResponseStatus(HttpStatus.CREATED)
    public TendencyEvalResult createTendencyResult(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        return diagnosisService.createTendencyResultForUser(request, userDetails.getUserId());
    }
}
