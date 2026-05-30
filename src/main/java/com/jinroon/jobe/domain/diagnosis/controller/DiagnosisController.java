package com.jinroon.jobe.domain.diagnosis.controller;

import com.jinroon.jobe.domain.diagnosis.controller.api.DiagnosisApi;
import com.jinroon.jobe.domain.diagnosis.dto.response.InProgressSessionResponse;
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
    @GetMapping("/sessions/me/in-progress")
    public InProgressSessionResponse getInProgressSession(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return diagnosisService.getInProgressSession(userDetails.getUserId());
    }

    @Override
    @GetMapping("/sessions/{sessionId}")
    public DiagnosisSession getSession(@PathVariable Long sessionId) {
        return diagnosisService.getSession(sessionId);
    }

    @Override
    @GetMapping("/sessions/me")
    public List<DiagnosisSession> findSessionsByUser(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return diagnosisService.findSessionsByUser(userDetails.getUserId());
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
    public List<DiagnosisExamAnswer> findExamAnswers(@PathVariable Long sessionId) {
        return diagnosisService.findExamAnswers(sessionId);
    }

    @Override
    @GetMapping("/sessions/{sessionId}/essay-answers")
    public List<DiagnosisEssayAnswer> findEssayAnswers(@PathVariable Long sessionId) {
        return diagnosisService.findEssayAnswers(sessionId);
    }

    @Override
    @GetMapping("/sessions/{sessionId}/competency-result")
    public CompetencyEvalResult getCompetencyResult(@PathVariable Long sessionId) {
        return diagnosisService.getCompetencyResult(sessionId);
    }

    @Override
    @GetMapping("/sessions/{sessionId}/tendency-result")
    public TendencyEvalResult getTendencyResult(@PathVariable Long sessionId) {
        return diagnosisService.getTendencyResult(sessionId);
    }

    @Override
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public DiagnosisSession createSession(@RequestBody Map<String, Object> request) {
        return diagnosisService.createSession(request);
    }

    @Override
    @PatchMapping("/sessions/{sessionId}")
    public DiagnosisSession updateSession(@PathVariable Long sessionId, @RequestBody Map<String, Object> request) {
        return diagnosisService.updateSession(sessionId, request);
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
    public DiagnosisExamAnswer createExamAnswer(@RequestBody Map<String, Object> request) {
        return diagnosisService.createExamAnswer(request);
    }

    @Override
    @PostMapping("/essay-answers")
    @ResponseStatus(HttpStatus.CREATED)
    public DiagnosisEssayAnswer createEssayAnswer(@RequestBody Map<String, Object> request) {
        return diagnosisService.createEssayAnswer(request);
    }

    @Override
    @PostMapping("/competency-results")
    @ResponseStatus(HttpStatus.CREATED)
    public CompetencyEvalResult createCompetencyResult(@RequestBody Map<String, Object> request) {
        return diagnosisService.createCompetencyResult(request);
    }

    @Override
    @PostMapping("/tendency-results")
    @ResponseStatus(HttpStatus.CREATED)
    public TendencyEvalResult createTendencyResult(@RequestBody Map<String, Object> request) {
        return diagnosisService.createTendencyResult(request);
    }
}
