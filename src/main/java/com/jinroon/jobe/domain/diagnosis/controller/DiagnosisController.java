package com.jinroon.jobe.domain.diagnosis.controller;

import com.jinroon.jobe.domain.diagnosis.controller.api.DiagnosisApi;
import com.jinroon.jobe.domain.diagnosis.dto.request.CompetencyEvalResultRequest;
import com.jinroon.jobe.domain.diagnosis.dto.request.DiagnosisEssayAnswerRequest;
import com.jinroon.jobe.domain.diagnosis.dto.request.DiagnosisExamAnswerRequest;
import com.jinroon.jobe.domain.diagnosis.dto.request.DiagnosisSessionRequest;
import com.jinroon.jobe.domain.diagnosis.dto.request.ExamQuestionRequest;
import com.jinroon.jobe.domain.diagnosis.dto.request.TendencyEvalResultRequest;
import com.jinroon.jobe.domain.diagnosis.dto.response.InProgressSessionResponse;
import com.jinroon.jobe.domain.diagnosis.entity.*;
import com.jinroon.jobe.domain.diagnosis.service.DiagnosisScoringService;
import com.jinroon.jobe.domain.diagnosis.service.DiagnosisService;
import com.jinroon.jobe.global.common.dto.RequestMapMapper;
import com.jinroon.jobe.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import java.util.List;
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
    private final DiagnosisScoringService diagnosisScoringService;

    @Override
    @GetMapping("/sessions/me/in-progress")
    public InProgressSessionResponse getInProgressSession(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return diagnosisService.getInProgressSession(userDetails.getUserId());
    }

    @Override
    @GetMapping("/sessions/{sessionId}")
    public DiagnosisSession getSession(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long sessionId) {
        return diagnosisService.getSessionForUser(sessionId, userDetails.getUserId());
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
    @PostMapping("/sessions/{sessionId}/score")
    public CompetencyEvalResult scoreCompetency(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long sessionId) {
        return diagnosisScoringService.scoreCompetencyForUser(sessionId, userDetails.getUserId());
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
            @Valid @RequestBody DiagnosisSessionRequest request) {
        return diagnosisService.createSessionForUser(RequestMapMapper.toMap(request), userDetails.getUserId());
    }

    @Override
    @PatchMapping("/sessions/{sessionId}")
    public DiagnosisSession updateSession(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long sessionId,
            @Valid @RequestBody DiagnosisSessionRequest request) {
        return diagnosisService.updateSessionForUser(sessionId, RequestMapMapper.toMap(request), userDetails.getUserId());
    }

    @Override
    @PostMapping("/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public ExamQuestion createQuestion(@Valid @RequestBody ExamQuestionRequest request) {
        return diagnosisService.createQuestion(RequestMapMapper.toMap(request));
    }

    @Override
    @PostMapping("/exam-answers")
    @ResponseStatus(HttpStatus.CREATED)
    public DiagnosisExamAnswer createExamAnswer(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody DiagnosisExamAnswerRequest request) {
        return diagnosisService.createExamAnswerForUser(RequestMapMapper.toMap(request), userDetails.getUserId());
    }

    @Override
    @PostMapping("/essay-answers")
    @ResponseStatus(HttpStatus.CREATED)
    public DiagnosisEssayAnswer createEssayAnswer(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody DiagnosisEssayAnswerRequest request) {
        return diagnosisService.createEssayAnswerForUser(RequestMapMapper.toMap(request), userDetails.getUserId());
    }

    @Override
    @PostMapping("/competency-results")
    @ResponseStatus(HttpStatus.CREATED)
    public CompetencyEvalResult createCompetencyResult(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody CompetencyEvalResultRequest request) {
        return diagnosisService.createCompetencyResultForUser(RequestMapMapper.toMap(request), userDetails.getUserId());
    }

    @Override
    @PostMapping("/tendency-results")
    @ResponseStatus(HttpStatus.CREATED)
    public TendencyEvalResult createTendencyResult(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody TendencyEvalResultRequest request) {
        return diagnosisService.createTendencyResultForUser(RequestMapMapper.toMap(request), userDetails.getUserId());
    }
}
