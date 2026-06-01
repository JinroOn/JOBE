package com.jinroon.jobe.domain.result.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.client.AiServiceClient;
import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest;
import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest.Profile;
import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest.TopMajor;
import com.jinroon.jobe.global.client.dto.response.RecommendationCommentResponse;
import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.domain.diagnosis.entity.CompetencyEvalResult;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.diagnosis.repository.CompetencyEvalResultRepository;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisSessionRepository;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.entity.ResultMajorScore;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.domain.result.repository.ResultMajorScoreRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final DiagnosisSessionRepository diagnosisSessionRepository;
    private final CompetencyEvalResultRepository competencyEvalResultRepository;
    private final MajorRepository majorRepository;
    private final AiServiceClient aiServiceClient;

    public List<DiagnosisResult> findResults(Long userId) {
        return userId == null ? diagnosisResultRepository.findAll() : diagnosisResultRepository.findByUserId(userId);
    }

    public DiagnosisResult getResult(Long resultId) {
        return get(diagnosisResultRepository, resultId, ErrorCode.RESULT_NOT_FOUND);
    }

    public DiagnosisResult getResultForUser(Long resultId, Long userId) {
        DiagnosisResult result = getResult(resultId);
        requireOwner(result.getUserId(), userId);
        return result;
    }

    public DiagnosisResult getSharedResult(String shareToken) {
        return diagnosisResultRepository.findByShareToken(shareToken)
                .orElseThrow(() -> new CustomException(ErrorCode.RESULT_SHARE_TOKEN_NOT_FOUND));
    }

    public List<ResultMajorScore> findMajorScores(Long resultId) {
        return resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(resultId);
    }

    public List<ResultMajorScore> findMajorScoresForUser(Long resultId, Long userId) {
        getResultForUser(resultId, userId);
        return findMajorScores(resultId);
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
    public DiagnosisResult createResultForUser(Map<String, Object> values, Long userId) {
        Long sessionId = ((Number) values.get("diagnosisSessionId")).longValue();
        DiagnosisSession session = get(diagnosisSessionRepository, sessionId, ErrorCode.DIAGNOSIS_SESSION_NOT_FOUND);
        requireOwner(session.getUserId(), userId);
        values.put("userId", userId);
        return createResult(values);
    }

    @Transactional
    public DiagnosisResult updateResult(Long resultId, Map<String, Object> values) {
        DiagnosisResult result = getResult(resultId);
        EntityFormMapper.apply(result, values);
        return result;
    }

    @Transactional
    public DiagnosisResult updateResultForUser(Long resultId, Map<String, Object> values, Long userId) {
        DiagnosisResult result = getResultForUser(resultId, userId);
        values.remove("userId");
        EntityFormMapper.apply(result, values);
        return result;
    }

    @Transactional
    public ResultMajorScore createMajorScore(Map<String, Object> values) {
        return resultMajorScoreRepository.save(EntityFormMapper.create(ResultMajorScore.class, values));
    }

    @Transactional
    public ResultMajorScore createMajorScoreForUser(Map<String, Object> values, Long userId) {
        Long resultId = ((Number) values.get("diagnosisResultId")).longValue();
        getResultForUser(resultId, userId);
        return createMajorScore(values);
    }

    private static void requireOwner(Long ownerId, Long userId) {
        if (!Objects.equals(ownerId, userId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
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

        Profile profile = new Profile(
                safeInt(competency.getMathLogic()),
                safeInt(competency.getProblemSolving()),
                safeInt(competency.getInfoTech()),
                safeInt(competency.getImplementation()),
                safeInt(competency.getSystemUnderstanding()),
                safeInt(competency.getDataAnalysis()),
                safeInt(competency.getCommunication()),
                safeInt(competency.getCollaboration()),
                safeInt(competency.getSelfManagement())
        );

        List<Long> majorIds = majorScores.stream().map(ResultMajorScore::getMajorId).toList();
        Map<Long, String> majorNameMap = majorRepository.findAllById(majorIds).stream()
                .collect(Collectors.toMap(Major::getId, Major::getName));

        List<TopMajor> topMajors = majorScores.stream()
                .map(score -> new TopMajor(
                        majorNameMap.getOrDefault(score.getMajorId(), "Unknown"),
                        score.getRank(),
                        score.getFinalScore() != null ? score.getFinalScore().doubleValue() : 0.0,
                        null,
                        null,
                        null
                ))
                .toList();

        RecommendationCommentRequest request = new RecommendationCommentRequest(
                result.getDiagnosisSessionId(), profile, topMajors, List.of(), null);

        RecommendationCommentResponse response = aiServiceClient.getRecommendationComment(request);
        if (response == null) {
            return;
        }

        result.applyAiComment(
                response.summaryComment(),
                String.join(",", response.weaknessFocus() != null ? response.weaknessFocus() : List.of())
        );

        if (response.majorComments() == null || response.majorComments().isEmpty()) {
            return;
        }

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

    private static int safeInt(Float value) {
        if (value == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, Math.round(value)));
    }
}
