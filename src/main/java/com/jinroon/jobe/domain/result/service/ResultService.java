package com.jinroon.jobe.domain.result.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.client.AiServiceClient;
import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest;
import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest.MajorInfo;
import com.jinroon.jobe.global.client.dto.response.RecommendationCommentResponse;
import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.domain.diagnosis.entity.CompetencyEvalResult;
import com.jinroon.jobe.domain.diagnosis.repository.CompetencyEvalResultRepository;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.entity.ResultMajorScore;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.domain.result.repository.ResultMajorScoreRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ResultService {

    private final DiagnosisResultRepository diagnosisResultRepository;
    private final ResultMajorScoreRepository resultMajorScoreRepository;
    private final CompetencyEvalResultRepository competencyEvalResultRepository;
    private final MajorRepository majorRepository;
    private final AiServiceClient aiServiceClient;

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
        DiagnosisResult result = diagnosisResultRepository.save(
                EntityFormMapper.create(DiagnosisResult.class, values));

        try {
            applyAiRecommendation(result);
        } catch (Exception e) {
            log.warn("AI 추천 코멘트 적용 실패 (resultId={}): {}", result.getId(), e.getMessage());
        }

        return result;
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

    private void applyAiRecommendation(DiagnosisResult result) {
        Optional<CompetencyEvalResult> competencyOpt =
                competencyEvalResultRepository.findByDiagnosisSessionId(result.getDiagnosisSessionId());
        if (competencyOpt.isEmpty()) {
            return;
        }
        CompetencyEvalResult competency = competencyOpt.get();

        List<ResultMajorScore> majorScores =
                resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(result.getId());
        if (majorScores.isEmpty()) {
            return;
        }

        Map<String, Double> profile = Map.of(
                "math_logic", competency.getMathLogic().doubleValue(),
                "problem_solving", competency.getProblemSolving().doubleValue(),
                "info_tech", competency.getInfoTech().doubleValue(),
                "implementation", competency.getImplementation().doubleValue(),
                "system_understanding", competency.getSystemUnderstanding().doubleValue(),
                "data_analysis", competency.getDataAnalysis().doubleValue(),
                "communication", competency.getCommunication().doubleValue(),
                "collaboration", competency.getCollaboration().doubleValue(),
                "self_management", competency.getSelfManagement().doubleValue()
        );

        List<Long> majorIds = majorScores.stream().map(ResultMajorScore::getMajorId).toList();
        Map<Long, String> majorNameMap = majorRepository.findAllById(majorIds).stream()
                .collect(Collectors.toMap(Major::getId, Major::getName));

        List<MajorInfo> topMajors = majorScores.stream()
                .map(score -> new MajorInfo(
                        majorNameMap.getOrDefault(score.getMajorId(), "Unknown"),
                        score.getRank(),
                        score.getFinalScore() != null ? score.getFinalScore().doubleValue() : 0.0,
                        null,
                        null
                ))
                .toList();

        RecommendationCommentRequest request = new RecommendationCommentRequest(
                result.getDiagnosisSessionId(), profile, topMajors, null);

        RecommendationCommentResponse response = aiServiceClient.getRecommendationComment(request);
        if (response == null) {
            return;
        }

        result.applyAiComment(
                response.summaryComment(),
                String.join(",", response.weaknessFocus())
        );

        Map<String, RecommendationCommentResponse.MajorComment> commentByName = response.majorComments()
                .stream()
                .collect(Collectors.toMap(RecommendationCommentResponse.MajorComment::majorName, c -> c));

        for (ResultMajorScore score : majorScores) {
            String majorName = majorNameMap.get(score.getMajorId());
            if (majorName == null) {
                continue;
            }
            RecommendationCommentResponse.MajorComment comment = commentByName.get(majorName);
            if (comment == null) {
                continue;
            }
            score.applyAiComment(comment.strengths(), comment.weaknesses(), comment.recommendationReason());
        }
    }
}
