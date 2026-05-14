package com.jinroon.jobe.result.service;

import static com.jinroon.jobe.common.domain.EntityLookup.get;

import com.jinroon.jobe.common.domain.EntityFormMapper;
import com.jinroon.jobe.common.error.ResourceNotFoundException;
import com.jinroon.jobe.result.domain.DiagnosisResult;
import com.jinroon.jobe.result.domain.ResultMajorScore;
import com.jinroon.jobe.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.result.repository.ResultMajorScoreRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ResultService {

    private final DiagnosisResultRepository diagnosisResultRepository;
    private final ResultMajorScoreRepository resultMajorScoreRepository;

    public List<DiagnosisResult> findResults(Long userId) {
        return userId == null ? diagnosisResultRepository.findAll() : diagnosisResultRepository.findByUserId(userId);
    }

    public DiagnosisResult getResult(Long resultId) {
        return get(diagnosisResultRepository, resultId, "DiagnosisResult");
    }

    public DiagnosisResult getSharedResult(String shareToken) {
        return diagnosisResultRepository.findByShareToken(shareToken)
                .orElseThrow(() -> new ResourceNotFoundException("DiagnosisResult not found. shareToken=" + shareToken));
    }

    public List<ResultMajorScore> findMajorScores(Long resultId) {
        return resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(resultId);
    }

    @Transactional
    public DiagnosisResult createResult(Map<String, Object> values) {
        return diagnosisResultRepository.save(EntityFormMapper.create(DiagnosisResult.class, values));
    }

    @Transactional
    public DiagnosisResult updateResult(Long resultId, Map<String, Object> values) {
        DiagnosisResult result = getResult(resultId);
        EntityFormMapper.apply(result, values);
        return result;
    }

    @Transactional
    public ResultMajorScore createMajorScore(Map<String, Object> values) {
        return resultMajorScoreRepository.save(EntityFormMapper.create(ResultMajorScore.class, values));
    }
}
