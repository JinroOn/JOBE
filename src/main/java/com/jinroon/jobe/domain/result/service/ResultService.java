package com.jinroon.jobe.domain.result.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.entity.ResultMajorScore;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.domain.result.repository.ResultMajorScoreRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        return get(diagnosisResultRepository, resultId, ErrorCode.RESULT_NOT_FOUND);
    }

    public DiagnosisResult getSharedResult(String shareToken) {
        return diagnosisResultRepository.findByShareToken(shareToken)
                .orElseThrow(() -> new CustomException(ErrorCode.RESULT_SHARE_TOKEN_NOT_FOUND));
    }

    public List<ResultMajorScore> findMajorScores(Long resultId) {
        return resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(resultId);
    }

    @Transactional
    public DiagnosisResult createResult(Map<String, Object> values) {
        String shareToken = UUID.randomUUID().toString().replace("-", "");
        values.put("shareToken", shareToken);
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
