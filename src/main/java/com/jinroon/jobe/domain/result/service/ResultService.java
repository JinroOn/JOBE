package com.jinroon.jobe.domain.result.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.client.AiServiceClient;
import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest;
import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest.DifferencePoint;
import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest.Profile;
import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest.RecommendationGroup;
import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest.TopMajor;
import com.jinroon.jobe.global.client.dto.response.RecommendationCommentResponse;
import com.jinroon.jobe.global.common.ai.AiGenerationStatus;
import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.domain.diagnosis.entity.CompetencyEvalResult;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.diagnosis.repository.CompetencyEvalResultRepository;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisSessionRepository;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
import com.jinroon.jobe.domain.major.service.MajorDatasetContextService;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.entity.ResultMajorScore;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.domain.result.repository.ResultMajorScoreRepository;
import java.util.Comparator;
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
    private static final int AI_COMMENT_TARGET_MAJOR_LIMIT = 1;

    private final DiagnosisResultRepository diagnosisResultRepository;
    private final ResultMajorScoreRepository resultMajorScoreRepository;
    private final DiagnosisSessionRepository diagnosisSessionRepository;
    private final CompetencyEvalResultRepository competencyEvalResultRepository;
    private final MajorRepository majorRepository;
    private final AiServiceClient aiServiceClient;
    private final MajorDatasetContextService majorDatasetContextService;

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
        return diagnosisResultRepository.save(EntityFormMapper.create(DiagnosisResult.class, values));
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

    @Transactional
    public DiagnosisResult generateAiCommentForUser(Long resultId, Long userId) {
        return generateAiCommentForUser(resultId, userId, false);
    }

    @Transactional
    public DiagnosisResult generateAiCommentForUser(Long resultId, Long userId, boolean force) {
        DiagnosisResult result = getResultForUser(resultId, userId);
        return applyAiRecommendation(result, force);
    }

    @Transactional
    public DiagnosisResult generateAiComment(Long resultId) {
        return generateAiComment(resultId, false);
    }

    @Transactional
    public DiagnosisResult generateAiComment(Long resultId, boolean force) {
        DiagnosisResult result = getResult(resultId);
        return applyAiRecommendation(result, force);
    }

    private static void requireOwner(Long ownerId, Long userId) {
        if (!Objects.equals(ownerId, userId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }

    private DiagnosisResult applyAiRecommendation(DiagnosisResult result, boolean force) {
        if (result.getAiCommentStatus() == AiGenerationStatus.PENDING) {
            throw new CustomException(ErrorCode.AI_GENERATION_IN_PROGRESS);
        }
        if (result.getAiCommentStatus() == AiGenerationStatus.SUCCEEDED && !force) {
            log.info("AI recommendation comment regeneration skipped resultId={} status={} force={}",
                    result.getId(), result.getAiCommentStatus(), force);
            return result;
        }

        CompetencyEvalResult competency = competencyEvalResultRepository
                .findByDiagnosisSessionId(result.getDiagnosisSessionId())
                .orElseThrow(() -> new CustomException(ErrorCode.DIAGNOSIS_COMPETENCY_RESULT_NOT_FOUND));

        List<ResultMajorScore> majorScores =
                resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(result.getId());
        if (majorScores.isEmpty()) {
            throw new CustomException(ErrorCode.RESULT_MAJOR_SCORE_NOT_FOUND);
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

        List<ResultMajorScore> sortedMajorScores = majorScores.stream()
                .sorted(Comparator.comparingInt(score -> safeRank(score.getRank())))
                .toList();
        List<ResultMajorScore> aiTargetMajorScores = sortedMajorScores.stream()
                .limit(AI_COMMENT_TARGET_MAJOR_LIMIT)
                .toList();

        List<Long> majorIds = aiTargetMajorScores.stream().map(ResultMajorScore::getMajorId).toList();
        Map<Long, Major> majorMap = majorRepository.findAllById(majorIds).stream()
                .collect(Collectors.toMap(Major::getId, major -> major));
        Map<Long, String> majorNameMap = majorMap.values().stream()
                .collect(Collectors.toMap(Major::getId, Major::getName));

        List<TopMajor> topMajors = aiTargetMajorScores.stream()
                .map(score -> new TopMajor(
                        majorNameMap.getOrDefault(score.getMajorId(), "Unknown"),
                        score.getRank(),
                        score.getFinalScore() != null ? score.getFinalScore().doubleValue() : 0.0,
                        null,
                        null,
                        recommendationMajorContext(majorMap.get(score.getMajorId()))
                ))
                .toList();

        List<RecommendationGroup> recommendationGroups = List.of();
        String targetMajorName = topMajors.isEmpty() ? "Unknown" : topMajors.get(0).majorName();

        RecommendationCommentRequest request = new RecommendationCommentRequest(
                result.getDiagnosisSessionId(), profile, topMajors, recommendationGroups, null);

        result.markAiCommentPending();
        RecommendationCommentResponse response = aiServiceClient.getRecommendationComment(request);
        if (response == null) {
            result.markAiCommentFailed("ai-service recommendation response is null");
            log.warn("AI 추천 코멘트 응답 없음 (resultId={}, requestMajorCount={}, targetMajor={})",
                    result.getId(), topMajors.size(), targetMajorName);
            return result;
        }

        result.applyAiComment(
                response.summaryComment(),
                String.join(",", response.weaknessFocus() != null ? response.weaknessFocus() : List.of())
        );

        if (response.majorComments() == null || response.majorComments().isEmpty()) {
            result.markAiCommentFailed("ai-service recommendation response has no major comments");
            log.warn("AI 추천 코멘트 전공별 응답 없음 (resultId={}, requestId={})", result.getId(), response.requestId());
            return result;
        }

        Map<String, RecommendationCommentResponse.MajorComment> commentByNameAndRank = response.majorComments()
                .stream()
                .collect(Collectors.toMap(
                        c -> commentKey(c.majorName(), c.rankingOrder()),
                        c -> c,
                        (first, ignored) -> first
                ));
        Map<String, RecommendationCommentResponse.MajorComment> commentByName = response.majorComments()
                .stream()
                .collect(Collectors.toMap(
                        RecommendationCommentResponse.MajorComment::majorName,
                        c -> c,
                        (first, ignored) -> first
                ));

        for (ResultMajorScore score : aiTargetMajorScores) {
            String majorName = majorNameMap.get(score.getMajorId());
            if (majorName == null) {
                continue;
            }
            RecommendationCommentResponse.MajorComment comment =
                    commentByNameAndRank.get(commentKey(majorName, score.getRank()));
            if (comment == null) {
                comment = commentByName.get(majorName);
            }
            if (comment == null) {
                log.warn("AI 추천 코멘트 전공 매칭 실패 (resultId={}, majorName={}, rank={})",
                        result.getId(), majorName, score.getRank());
                continue;
            }
            score.applyAiComment(comment.strengths(), comment.weaknesses(), comment.recommendationReason());
        }

        result.markAiCommentSucceeded();
        log.info("AI 추천 코멘트 적용 완료 (resultId={}, requestId={}, majorCount={})",
                result.getId(), response.requestId(), response.majorComments().size());
        return result;
    }

    private static String commentKey(String majorName, Integer rankingOrder) {
        return majorName + "#" + rankingOrder;
    }

    private RecommendationCommentRequest.MajorContext recommendationMajorContext(Major major) {
        return major == null ? null : majorDatasetContextService.toRecommendationMajorContext(major);
    }

    private List<RecommendationGroup> buildRecommendationGroups(
            List<ResultMajorScore> majorScores,
            Map<Long, Major> majorMap,
            CompetencyEvalResult competency
    ) {
        if (majorScores.isEmpty()) {
            return List.of();
        }

        List<ResultMajorScore> sortedScores = majorScores.stream()
                .sorted(Comparator.comparingInt(score -> safeRank(score.getRank())))
                .toList();
        ResultMajorScore representative = sortedScores.get(0);
        String representativeName = majorName(representative, majorMap);

        List<String> similarMajorNames = sortedScores.stream()
                .skip(1)
                .map(score -> majorName(score, majorMap))
                .toList();
        List<DifferencePoint> differencePoints = sortedScores.stream()
                .map(score -> new DifferencePoint(
                        majorName(score, majorMap),
                        differenceDescription(score, majorMap.get(score.getMajorId()))
                ))
                .toList();

        return List.of(new RecommendationGroup(
                1,
                representativeName,
                safeRank(representative.getRank()),
                similarMajorNames,
                commonFitAxes(competency),
                differencePoints
        ));
    }

    private String majorName(ResultMajorScore score, Map<Long, Major> majorMap) {
        Major major = majorMap.get(score.getMajorId());
        return major != null && major.getName() != null ? major.getName() : "Unknown";
    }

    private String differenceDescription(ResultMajorScore score, Major major) {
        String majorName = major != null && major.getName() != null ? major.getName() : "해당 전공";
        List<AxisScore> requirementAxes = major == null ? List.of() : majorRequirementAxes(major);
        String axisText = requirementAxes.stream()
                .limit(2)
                .map(AxisScore::label)
                .collect(Collectors.joining("과 "));
        String categoryText = major != null && major.getCategory() != null && !major.getCategory().isBlank()
                ? major.getCategory() + " 분야에서 "
                : "";

        if (!axisText.isBlank()) {
            return "%s은 %s%s 역량을 중심으로 적합도를 보이는 전공입니다."
                    .formatted(majorName, categoryText, axisText);
        }

        double fitScore = score.getFinalScore() != null ? score.getFinalScore().doubleValue() : 0.0;
        return "%s은 추천 점수 %.1f점을 기준으로 사용자의 역량 프로필과 비교된 전공입니다."
                .formatted(majorName, fitScore);
    }

    private List<String> commonFitAxes(CompetencyEvalResult competency) {
        return competencyAxes(competency).stream()
                .sorted(Comparator.comparingInt(AxisScore::score).reversed())
                .limit(3)
                .map(AxisScore::fieldName)
                .toList();
    }

    private List<AxisScore> competencyAxes(CompetencyEvalResult competency) {
        return List.of(
                new AxisScore("mathLogicalScore", "수리논리", safeInt(competency.getMathLogic())),
                new AxisScore("problemSolvingScore", "문제해결", safeInt(competency.getProblemSolving())),
                new AxisScore("infoTechUtilizationScore", "정보기술", safeInt(competency.getInfoTech())),
                new AxisScore("softwareImplementationScore", "구현력", safeInt(competency.getImplementation())),
                new AxisScore("systemUnderstandingScore", "시스템이해", safeInt(competency.getSystemUnderstanding())),
                new AxisScore("dataAnalysisScore", "데이터분석", safeInt(competency.getDataAnalysis())),
                new AxisScore("communicationScore", "의사소통", safeInt(competency.getCommunication())),
                new AxisScore("collaborationScore", "협업", safeInt(competency.getCollaboration())),
                new AxisScore("selfManagementScore", "자기관리", safeInt(competency.getSelfManagement()))
        );
    }

    private List<AxisScore> majorRequirementAxes(Major major) {
        return List.of(
                new AxisScore("mathLogicalScore", "수리논리", safeInt(major.getReqMathLogic())),
                new AxisScore("problemSolvingScore", "문제해결", safeInt(major.getReqProblemSolving())),
                new AxisScore("infoTechUtilizationScore", "정보기술", safeInt(major.getReqInfoTech())),
                new AxisScore("softwareImplementationScore", "구현력", safeInt(major.getReqImplementation())),
                new AxisScore("systemUnderstandingScore", "시스템이해", safeInt(major.getReqSystemUnderstanding())),
                new AxisScore("dataAnalysisScore", "데이터분석", safeInt(major.getReqDataAnalysis())),
                new AxisScore("communicationScore", "의사소통", safeInt(major.getReqCommunication())),
                new AxisScore("collaborationScore", "협업", safeInt(major.getReqCollaboration())),
                new AxisScore("selfManagementScore", "자기관리", safeInt(major.getReqSelfManagement()))
        ).stream()
                .filter(axis -> axis.score() > 0)
                .sorted(Comparator.comparingInt(AxisScore::score).reversed())
                .toList();
    }

    private static int safeRank(Integer rank) {
        return rank == null ? 1 : rank;
    }

    private static int safeInt(Float value) {
        if (value == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, Math.round(value)));
    }

    private record AxisScore(String fieldName, String label, int score) {
    }
}
