package com.jinroon.jobe.diagnosis.controller;

import com.jinroon.jobe.diagnosis.domain.*;
import com.jinroon.jobe.diagnosis.service.DiagnosisService;
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
@RequestMapping("/api/diagnoses")
public class DiagnosisController {

    private final DiagnosisService diagnosisService;

    @GetMapping("/sessions/{sessionId}")
    public DiagnosisSession getSession(@PathVariable Long sessionId) {
        return diagnosisService.getSession(sessionId);
    }

    @GetMapping("/users/{userId}/sessions")
    public List<DiagnosisSession> findSessionsByUser(@PathVariable Long userId) {
        return diagnosisService.findSessionsByUser(userId);
    }

    @GetMapping("/questions")
    public List<ExamQuestion> findQuestions() {
        return diagnosisService.findQuestions();
    }

    @GetMapping("/questions/{questionId}")
    public ExamQuestion getQuestion(@PathVariable Long questionId) {
        return diagnosisService.getQuestion(questionId);
    }

    @GetMapping("/sessions/{sessionId}/exam-answers")
    public List<DiagnosisExamAnswer> findExamAnswers(@PathVariable Long sessionId) {
        return diagnosisService.findExamAnswers(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/essay-answers")
    public List<DiagnosisEssayAnswer> findEssayAnswers(@PathVariable Long sessionId) {
        return diagnosisService.findEssayAnswers(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/competency-result")
    public CompetencyEvalResult getCompetencyResult(@PathVariable Long sessionId) {
        return diagnosisService.getCompetencyResult(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/tendency-result")
    public TendencyEvalResult getTendencyResult(@PathVariable Long sessionId) {
        return diagnosisService.getTendencyResult(sessionId);
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public DiagnosisSession createSession(@RequestBody Map<String, Object> request) {
        return diagnosisService.createSession(request);
    }

    @PatchMapping("/sessions/{sessionId}")
    public DiagnosisSession updateSession(@PathVariable Long sessionId, @RequestBody Map<String, Object> request) {
        return diagnosisService.updateSession(sessionId, request);
    }

    @PostMapping("/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public ExamQuestion createQuestion(@RequestBody Map<String, Object> request) {
        return diagnosisService.createQuestion(request);
    }

    @PostMapping("/exam-answers")
    @ResponseStatus(HttpStatus.CREATED)
    public DiagnosisExamAnswer createExamAnswer(@RequestBody Map<String, Object> request) {
        return diagnosisService.createExamAnswer(request);
    }

    @PostMapping("/essay-answers")
    @ResponseStatus(HttpStatus.CREATED)
    public DiagnosisEssayAnswer createEssayAnswer(@RequestBody Map<String, Object> request) {
        return diagnosisService.createEssayAnswer(request);
    }

    @PostMapping("/competency-results")
    @ResponseStatus(HttpStatus.CREATED)
    public CompetencyEvalResult createCompetencyResult(@RequestBody Map<String, Object> request) {
        return diagnosisService.createCompetencyResult(request);
    }

    @PostMapping("/tendency-results")
    @ResponseStatus(HttpStatus.CREATED)
    public TendencyEvalResult createTendencyResult(@RequestBody Map<String, Object> request) {
        return diagnosisService.createTendencyResult(request);
    }
}
