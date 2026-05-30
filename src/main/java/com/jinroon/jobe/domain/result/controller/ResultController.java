package com.jinroon.jobe.domain.result.controller;

import com.jinroon.jobe.domain.result.controller.api.ResultApi;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.entity.ResultMajorScore;
import com.jinroon.jobe.domain.result.service.ResultService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/results")
public class ResultController implements ResultApi {

    private final ResultService resultService;

    @Override
    @GetMapping
    public List<DiagnosisResult> findResults(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) Long userId) {
        return resultService.findResultsForUser(userId, userDetails.getUserId());
    }

    @Override
    @GetMapping("/{resultId}")
    public DiagnosisResult getResult(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long resultId) {
        return resultService.getResultForUser(resultId, userDetails.getUserId());
    }

    @Override
    @GetMapping("/share/{shareToken}")
    public DiagnosisResult getSharedResult(@PathVariable String shareToken) {
        return resultService.getSharedResult(shareToken);
    }

    @Override
    @GetMapping("/{resultId}/major-scores")
    public List<ResultMajorScore> findMajorScores(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long resultId) {
        return resultService.findMajorScoresForUser(resultId, userDetails.getUserId());
    }

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DiagnosisResult createResult(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        return resultService.createResultForUser(request, userDetails.getUserId());
    }

    @Override
    @PatchMapping("/{resultId}")
    public DiagnosisResult updateResult(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long resultId,
            @RequestBody Map<String, Object> request) {
        return resultService.updateResultForUser(resultId, userDetails.getUserId(), request);
    }

    @Override
    @PostMapping("/major-scores")
    @ResponseStatus(HttpStatus.CREATED)
    public ResultMajorScore createMajorScore(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody Map<String, Object> request) {
        return resultService.createMajorScoreForUser(request, userDetails.getUserId());
    }
}
