package com.jinroon.jobe.result.controller;

import com.jinroon.jobe.result.domain.DiagnosisResult;
import com.jinroon.jobe.result.domain.ResultMajorScore;
import com.jinroon.jobe.result.service.ResultService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/results")
public class ResultController {

    private final ResultService resultService;

    @GetMapping
    public List<DiagnosisResult> findResults(@RequestParam(required = false) Long userId) {
        return resultService.findResults(userId);
    }

    @GetMapping("/{resultId}")
    public DiagnosisResult getResult(@PathVariable Long resultId) {
        return resultService.getResult(resultId);
    }

    @GetMapping("/share/{shareToken}")
    public DiagnosisResult getSharedResult(@PathVariable String shareToken) {
        return resultService.getSharedResult(shareToken);
    }

    @GetMapping("/{resultId}/major-scores")
    public List<ResultMajorScore> findMajorScores(@PathVariable Long resultId) {
        return resultService.findMajorScores(resultId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DiagnosisResult createResult(@RequestBody Map<String, Object> request) {
        return resultService.createResult(request);
    }

    @PatchMapping("/{resultId}")
    public DiagnosisResult updateResult(@PathVariable Long resultId, @RequestBody Map<String, Object> request) {
        return resultService.updateResult(resultId, request);
    }

    @PostMapping("/major-scores")
    @ResponseStatus(HttpStatus.CREATED)
    public ResultMajorScore createMajorScore(@RequestBody Map<String, Object> request) {
        return resultService.createMajorScore(request);
    }
}
