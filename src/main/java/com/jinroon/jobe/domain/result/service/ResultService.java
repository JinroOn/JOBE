package com.jinroon.jobe.domain.result.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.diagnosis.dto.DiagnosisProfileAdjustment;
import com.jinroon.jobe.domain.diagnosis.dto.DiagnosisProfileSnapshot;
import com.jinroon.jobe.domain.diagnosis.entity.TendencyEvalResult;
import com.jinroon.jobe.global.client.AiServiceClient;
import com.jinroon.jobe.global.client.dto.request.DiagnosisProfileContext;
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
import com.jinroon.jobe.domain.diagnosis.repository.TendencyEvalResultRepository;
import com.jinroon.jobe.domain.diagnosis.service.DiagnosisProfileScoringService;
import com.jinroon.jobe.domain.diagnosis.service.TendencyScoringService;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
import com.jinroon.jobe.domain.major.service.MajorDatasetContextService;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlan;
import com.jinroon.jobe.domain.plan.service.PlanService;
import com.jinroon.jobe.domain.result.dto.response.SharedDiagnosisResultResponse;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.entity.ResultMajorScore;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.domain.result.repository.ResultMajorScoreRepository;
import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private static final int WEAKNESS_FOCUS_LIMIT = 2;
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private final DiagnosisResultRepository diagnosisResultRepository;
    private final ResultMajorScoreRepository resultMajorScoreRepository;
    private final DiagnosisSessionRepository diagnosisSessionRepository;
    private final CompetencyEvalResultRepository competencyEvalResultRepository;
    private final TendencyEvalResultRepository tendencyEvalResultRepository;
    private final MajorRepository majorRepository;
    private final DiagnosisProfileScoringService diagnosisProfileScoringService;
    private final UserRepository userRepository;
    private final AiServiceClient aiServiceClient;
    private final MajorDatasetContextService majorDatasetContextService;
    private final ObjectMapper objectMapper;
    private final PlanService planService;
    private final TendencyScoringService tendencyScoringService;
    private final com.jinroon.jobe.domain.user.service.UserWriteGuard writeGuard;
    private final com.jinroon.jobe.domain.plan.service.LegacyPlanTransactions transactions;

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

    public SharedDiagnosisResultResponse getSharedResultWithOwner(String shareToken) {
        DiagnosisResult result = getSharedResult(shareToken);
        String ownerNickname = userRepository.findById(result.getUserId())
                .map(User::getNickname)
                .orElse("알 수 없음");
        return SharedDiagnosisResultResponse.from(result, ownerNickname);
    }

    public List<ResultMajorScore> findMajorScores(Long resultId) {
        return resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(resultId);
    }

    public List<ResultMajorScore> findMajorScoresForUser(Long resultId, Long userId) {
        getResultForUser(resultId, userId);
        return findMajorScores(resultId);
    }

    public List<ResultMajorScore> findMajorScoresByShareToken(String shareToken) {
        DiagnosisResult result = getSharedResult(shareToken);
        return findMajorScores(result.getId());
    }







    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public DiagnosisResult completeDiagnosisResultForUser(Long sessionId, Long userId) {
        CompletedLegacy completed=transactions.execute(()->completeLegacyResult(sessionId,userId));
        // The result transaction has fully cleaned up its connection before provider I/O starts.
        createInitialPlanIfAbsent(completed.result().getId(),completed.scores());
        return completed.result();
    }

    private record CompletedLegacy(DiagnosisResult result,List<ResultMajorScore> scores){}

    private CompletedLegacy completeLegacyResult(Long sessionId,Long userId) {
        writeGuard.requireActive(userId);
        DiagnosisSession session = get(diagnosisSessionRepository, sessionId, ErrorCode.DIAGNOSIS_SESSION_NOT_FOUND);
        requireOwner(session.getUserId(), userId);

        CompetencyEvalResult competency = competencyEvalResultRepository.findByDiagnosisSessionId(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.DIAGNOSIS_COMPETENCY_RESULT_NOT_FOUND));
        TendencyEvalResult tendency = tendencyEvalResultRepository.findByDiagnosisSessionId(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.DIAGNOSIS_TENDENCY_RESULT_NOT_FOUND));
        recomputeTendencyFromAnswers(session, tendency);

        List<Major> majors = majorRepository.findAllForReference();
        List<MajorScoreDraft> drafts = majors.stream()
                .map(major -> scoreMajor(session, competency, tendency, major))
                .sorted(ResultService::compareMajorScoreDrafts)
                .toList();

        String competencyVector = competencyVectorJson(competency);
        String tendencyVector = tendencyVectorJson(tendency);
        String topMajorsJson = topMajorsJson(drafts);

        DiagnosisResult result = diagnosisResultRepository.findByDiagnosisSessionId(sessionId)
                .map(existing -> {
                    existing.updateRecommendationData(competencyVector, tendencyVector, topMajorsJson);
                    existing.resetAiCommentForRecommendationChange();
                    return existing;
                })
                .orElseGet(() -> DiagnosisResult.create(
                        sessionId,
                        userId,
                        competencyVector,
                        tendencyVector,
                        topMajorsJson,
                        UUID.randomUUID().toString().replace("-", "")
                ));
        // AI 종합평을 만들기 전에도 화면에서 쓸 수 있게 점수로 계산해 둔다.
        result.applyWeaknessFocus(competency.relativelyWeakFields(WEAKNESS_FOCUS_LIMIT));
        result = diagnosisResultRepository.save(result);

        resultMajorScoreRepository.deleteAllByDiagnosisResultId(result.getId());
        List<ResultMajorScore> scores = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            MajorScoreDraft draft = drafts.get(i);
            scores.add(ResultMajorScore.of(
                    result.getId(),
                    draft.major().getId(),
                    draft.tendencyScore(),
                    draft.competencyScore(),
                    draft.finalScore(),
                    i + 1,
                    draft.failed(),
                    draft.recommendationReason()
            ));
        }
        List<ResultMajorScore> savedScores = resultMajorScoreRepository.saveAll(scores);
        session.complete(LocalDateTime.now());
        return new CompletedLegacy(result,savedScores);
    }

    @Transactional
    public DiagnosisResult generateAiCommentForUser(Long resultId, Long userId) {
        writeGuard.requireActive(userId);
        return generateAiCommentForUser(resultId, userId, false);
    }

    @Transactional
    public DiagnosisResult generateAiCommentForUser(Long resultId, Long userId, boolean force) {
        writeGuard.requireActive(userId);
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

    /**
     * 세션에 성향 응답(A/B 12문항)이 모두 있으면 저장된 성향 점수를 서버 계산값으로 덮어쓴다.
     * 응답이 없거나 불완전한 이전 회차는 저장된 값을 그대로 쓴다.
     */
    private void recomputeTendencyFromAnswers(DiagnosisSession session, TendencyEvalResult tendency) {
        Map<Integer, String> answers = tendencyScoringService.readAnswers(session.getInputSnapshot());
        if (!tendencyScoringService.isComplete(answers)) {
            return;
        }
        Map<TendencyScoringService.Axis, Float> scores = tendencyScoringService.score(answers);
        tendency.updateScores(
                scores.get(TendencyScoringService.Axis.LOGICAL_INQUIRY),
                scores.get(TendencyScoringService.Axis.PRACTICAL_TECH),
                scores.get(TendencyScoringService.Axis.ART_CREATIVE),
                scores.get(TendencyScoringService.Axis.SOCIAL_COOPERATION),
                scores.get(TendencyScoringService.Axis.LIFE_HEALTH),
                scores.get(TendencyScoringService.Axis.EDUCATION_GUIDE),
                scores.get(TendencyScoringService.Axis.THEORY_ACADEMIC),
                scores.get(TendencyScoringService.Axis.DATA_ANALYTICS),
                scores.get(TendencyScoringService.Axis.SYSTEM_OPERATION)
        );
    }

    private static void requireOwner(Long ownerId, Long userId) {
        if (!Objects.equals(ownerId, userId)) {
            throw new CustomException(ErrorCode.RESULT_NOT_FOUND);
        }
    }



    private MajorScoreDraft scoreMajor(
            DiagnosisSession session,
            CompetencyEvalResult competency,
            TendencyEvalResult tendency,
            Major major
    ) {
        float competencyScore = computeCompetencyScore(competency, major);
        float tendencyScore = computeTendencyScore(tendency, major);
        float baseFinalScore = Math.round(competencyScore * 0.6f + tendencyScore * 0.4f);
        DiagnosisProfileAdjustment adjustment =
                diagnosisProfileScoringService.calculateProfileAdjustment(session, major);
        float finalScore = diagnosisProfileScoringService.adjustedFinalScore(
                competencyScore,
                tendencyScore,
                baseFinalScore,
                adjustment.bonus()
        );
        return new MajorScoreDraft(
                major,
                competencyScore,
                tendencyScore,
                finalScore,
                failedThreshold(competency, major),
                adjustment.conciseReason()
        );
    }

    private static int compareMajorScoreDrafts(MajorScoreDraft first, MajorScoreDraft second) {
        return Comparator
                .comparing(MajorScoreDraft::failed)
                .thenComparing(MajorScoreDraft::finalScore, Comparator.reverseOrder())
                .thenComparing(MajorScoreDraft::competencyScore, Comparator.reverseOrder())
                .thenComparing(draft -> draft.major().getId(), Comparator.nullsLast(Long::compareTo))
                .compare(first, second);
    }

    private static float computeCompetencyScore(CompetencyEvalResult competency, Major major) {
        return Math.round(averageFit(List.of(
                fitScore(competency.getMathLogic(), major.getReqMathLogic()),
                fitScore(competency.getProblemSolving(), major.getReqProblemSolving()),
                fitScore(competency.getInfoTech(), major.getReqInfoTech()),
                fitScore(competency.getImplementation(), major.getReqImplementation()),
                fitScore(competency.getSystemUnderstanding(), major.getReqSystemUnderstanding()),
                fitScore(competency.getDataAnalysis(), major.getReqDataAnalysis()),
                fitScore(competency.getCommunication(), major.getReqCommunication()),
                fitScore(competency.getCollaboration(), major.getReqCollaboration()),
                fitScore(competency.getSelfManagement(), major.getReqSelfManagement())
        )));
    }

    private static float computeTendencyScore(TendencyEvalResult tendency, Major major) {
        return Math.round(averageFit(List.of(
                fitScore(tendency.getLogicalInquiry(), major.getTendLogicalInquiry()),
                fitScore(tendency.getPracticalTech(), major.getTendPracticalTech()),
                fitScore(tendency.getArtCreative(), major.getTendArtCreative()),
                fitScore(tendency.getSocialCooperation(), major.getTendSocialCooperation()),
                fitScore(tendency.getLifeHealth(), major.getTendLifeHealth()),
                fitScore(tendency.getEducationGuide(), major.getTendEducationGuide()),
                fitScore(tendency.getTheoryAcademic(), major.getTendTheoryAcademic()),
                fitScore(tendency.getDataAnalytics(), major.getTendDataAnalytics()),
                fitScore(tendency.getSystemOperation(), major.getTendSystemOperation())
        )));
    }

    private static float averageFit(List<Float> scores) {
        if (scores == null || scores.isEmpty()) {
            return 0.0f;
        }
        float total = 0.0f;
        for (Float score : scores) {
            total += score == null ? 0.0f : score;
        }
        return total / scores.size();
    }

    private static float fitScore(Float userScore, Float majorRequirement) {
        float requirement = safeFloat(majorRequirement);
        if (requirement <= 0.0f) {
            return 100.0f;
        }
        return Math.min((safeFloat(userScore) / requirement) * 100.0f, 100.0f);
    }

    private static boolean failedThreshold(CompetencyEvalResult competency, Major major) {
        return thresholdFailed(competency.getMathLogic(), major.getThrMathLogic())
                || thresholdFailed(competency.getInfoTech(), major.getThrInfoTech());
    }

    private static boolean thresholdFailed(Float userScore, Float threshold) {
        return threshold != null && threshold > 0.0f && safeFloat(userScore) < threshold;
    }

    private String competencyVectorJson(CompetencyEvalResult competency) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("mathLogic", safeFloat(competency.getMathLogic()));
        values.put("problemSolving", safeFloat(competency.getProblemSolving()));
        values.put("infoTech", safeFloat(competency.getInfoTech()));
        values.put("implementation", safeFloat(competency.getImplementation()));
        values.put("systemUnderstanding", safeFloat(competency.getSystemUnderstanding()));
        values.put("dataAnalysis", safeFloat(competency.getDataAnalysis()));
        values.put("communication", safeFloat(competency.getCommunication()));
        values.put("collaboration", safeFloat(competency.getCollaboration()));
        values.put("selfManagement", safeFloat(competency.getSelfManagement()));
        return toJson(values);
    }

    private String tendencyVectorJson(TendencyEvalResult tendency) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tendLogicalInquiry", safeFloat(tendency.getLogicalInquiry()));
        values.put("tendPracticalTech", safeFloat(tendency.getPracticalTech()));
        values.put("tendArtCreative", safeFloat(tendency.getArtCreative()));
        values.put("tendSocialCooperation", safeFloat(tendency.getSocialCooperation()));
        values.put("tendLifeHealth", safeFloat(tendency.getLifeHealth()));
        values.put("tendEducationGuide", safeFloat(tendency.getEducationGuide()));
        values.put("tendTheoryAcademic", safeFloat(tendency.getTheoryAcademic()));
        values.put("tendDataAnalytics", safeFloat(tendency.getDataAnalytics()));
        values.put("tendSystemOperation", safeFloat(tendency.getSystemOperation()));
        return toJson(values);
    }

    private String topMajorsJson(List<MajorScoreDraft> drafts) {
        List<Long> topMajorIds = drafts.stream()
                .filter(draft -> !draft.failed())
                .limit(3)
                .map(draft -> draft.major().getId())
                .toList();
        if (topMajorIds.isEmpty()) {
            topMajorIds = drafts.stream()
                    .limit(3)
                    .map(draft -> draft.major().getId())
                    .toList();
        }
        return toJson(topMajorIds);
    }

    private void createInitialPlanIfAbsent(Long resultId, List<ResultMajorScore> scores) {
        if (resultId == null || scores == null || scores.isEmpty()) {
            return;
        }
        List<MajorWeeklyPlan> existingPlans = Optional.ofNullable(planService.findPlansByResult(resultId))
                .orElse(List.of());
        boolean hasActivePlan = existingPlans.stream().anyMatch(plan -> Boolean.TRUE.equals(plan.getActiveVersion()));
        if (hasActivePlan) {
            return;
        }

        scores.stream()
                .filter(score -> score.getId() != null)
                .filter(score -> !Boolean.TRUE.equals(score.getFailed()))
                .findFirst()
                .or(() -> scores.stream().filter(score -> score.getId() != null).findFirst())
                .ifPresent(score -> {
                    Long scoreId = score.getId();
                    try {
                        planService.createPlan(Map.of("diagnosisResultId", resultId, "resultMajorScoreId", scoreId,
                                "activeVersion", true));
                    } catch (Exception failure) {
                        log.warn("Initial weekly plan creation skipped resultId={} resultMajorScoreId={} errorType={}",
                                resultId, scoreId, failure.getClass().getSimpleName());
                    }
                });
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("diagnosis result JSON serialization failed errorType={} message={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return value instanceof List<?> ? "[]" : "{}";
        }
    }

    private static Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Float floatValue(Object value) {
        return value instanceof Number number ? number.floatValue() : null;
    }

    private static boolean isBlank(Object value) {
        return value == null || value.toString().isBlank();
    }

    private static Float safeFinalScore(ResultMajorScore score) {
        return score.getFinalScore() == null ? -1.0f : score.getFinalScore();
    }

    private static float safeFloat(Float value) {
        return value == null ? 0.0f : Math.max(0.0f, Math.min(100.0f, value));
    }

    private DiagnosisResult applyAiRecommendation(DiagnosisResult result, boolean force) {
        if (result.getAiCommentStatus() == AiGenerationStatus.PENDING) {
            throw new CustomException(ErrorCode.AI_GENERATION_IN_PROGRESS);
        }
        if (result.getAiCommentStatus() == AiGenerationStatus.SUCCEEDED && !force
                && !result.hasFixedWeaknessComment()) {
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
        List<ResultMajorScore> aiTargetMajorScores = selectAiTargetMajorScores(sortedMajorScores);

        List<Long> majorIds = aiTargetMajorScores.stream().map(ResultMajorScore::getMajorId).toList();
        Map<Long, Major> majorMap = majorRepository.findAllById(majorIds).stream()
                .collect(Collectors.toMap(Major::getId, major -> major));
        Map<Long, String> majorNameMap = majorMap.values().stream()
                .collect(Collectors.toMap(Major::getId, major -> readableText(major.getName())));

        List<TopMajor> topMajors = aiTargetMajorScores.stream()
                .map(score -> new TopMajor(
                        majorNameMap.getOrDefault(score.getMajorId(), "Unknown"),
                        score.getRank(),
                        score.getFinalScore() != null ? score.getFinalScore().doubleValue() : 0.0,
                        topMajorStrengths(score, majorMap.get(score.getMajorId())),
                        topMajorWeaknesses(score, competency),
                        recommendationMajorContext(majorMap.get(score.getMajorId()))
                ))
                .toList();

        List<RecommendationGroup> recommendationGroups = List.of();
        String targetMajorName = topMajors.isEmpty() ? "Unknown" : topMajors.get(0).majorName();

        DiagnosisProfileSnapshot profileSnapshot = profileSnapshotFor(result);
        RecommendationCommentRequest request = new RecommendationCommentRequest(
                result.getDiagnosisSessionId(),
                profile,
                topMajors,
                recommendationGroups,
                userContextFrom(profileSnapshot),
                DiagnosisProfileContext.from(profileSnapshot)
        );

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
                String.join(",", response.weaknessFocus() != null && !response.weaknessFocus().isEmpty()
                        ? response.weaknessFocus()
                        : competency.relativelyWeakFields(WEAKNESS_FOCUS_LIMIT))
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

    private List<ResultMajorScore> selectAiTargetMajorScores(List<ResultMajorScore> sortedMajorScores) {
        return sortedMajorScores.stream()
                .limit(AI_COMMENT_TARGET_MAJOR_LIMIT)
                .toList();
    }

    private String topMajorStrengths(ResultMajorScore score, Major major) {
        String fitContext = "finalScore %.1f, competencyScore %.1f, tendencyScore %.1f"
                .formatted(
                        safeFloat(score.getFinalScore()),
                        safeFloat(score.getCompetencyScore()),
                        safeFloat(score.getTendencyScore())
                );
        String majorContext = major == null
                ? ""
                : " Major field context: name=%s, category=%s, description=%s, careerPaths=%s."
                .formatted(
                        readableText(major.getName()),
                        readableText(major.getCategory()),
                        truncateForPrompt(readableText(major.getDescription()), 180),
                        truncateForPrompt(readableText(major.getCareerPaths()), 160)
                );
        String reason = score.getRecommendationReason() == null || score.getRecommendationReason().isBlank()
                ? ""
                : " Saved recommendation reason: " + truncateForPrompt(readableText(score.getRecommendationReason()), 220) + ".";
        String hints = "This is the current rank 1 major. Use score context internally, but do not mention scoring mechanics, "
                + "quiz-based diagnosis, supporting signals, or saved scores in the user-facing text. Explain concrete fit points "
                + "between this major's field, career paths, required preparation, and the student's current strengths. Score context: "
                + fitContext + "." + reason + majorContext;
        return truncateForPrompt(hints, 500);
    }

    private String topMajorWeaknesses(ResultMajorScore score, CompetencyEvalResult competency) {
        String axes = competencyAxes(competency).stream()
                .sorted(Comparator.comparingInt(AxisScore::score))
                .limit(2)
                .map(AxisScore::label)
                .collect(Collectors.joining(", "));
        String failedContext = Boolean.TRUE.equals(score.getFailed())
                ? " This major passed after threshold caution, so do not overstate certainty."
                : "";
        if (axes.isBlank()) {
            return "Mention weak quiz competency areas only as preparation priorities for this rank 1 major. "
                    + "Do not reinterpret this major as another field." + failedContext;
        }
        return "Weak quiz competency areas for preparation: " + axes
                + ". Mention them only as preparation priorities for this rank 1 major, "
                + "not as a reason to describe another major family."
                + failedContext;
    }

    private RecommendationCommentRequest.MajorContext recommendationMajorContext(Major major) {
        if (major == null) {
            return null;
        }
        RecommendationCommentRequest.MajorContext context =
                majorDatasetContextService.toRecommendationMajorContext(major);
        if (context == null) {
            return new RecommendationCommentRequest.MajorContext(
                    truncateForPrompt(readableText(major.getCategory()), 30),
                    truncateForPrompt(readableText(major.getDescription()), 1000),
                    null,
                    readableCareerPathValues(major.getCareerPaths()),
                    List.of()
            );
        }
        return new RecommendationCommentRequest.MajorContext(
                truncateForPrompt(readableText(context.category()), 30),
                truncateForPrompt(readableText(context.description()), 1000),
                truncateForPrompt(readableText(context.sourceSummary()), 700),
                readableTextValues(context.relatedJobs()),
                readableTextValues(context.ragSnippets()).stream()
                        .map(value -> truncateForPrompt(value, 500))
                        .toList()
        );
    }

    private List<String> readableTextValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(ResultService::readableText)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private List<String> readableCareerPathValues(String careerPaths) {
        String readable = readableText(careerPaths);
        if (readable == null || readable.isBlank()) {
            return List.of();
        }
        return List.of(readable);
    }

    private static String truncateForPrompt(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String readableText(String value) {
        if (value == null || value.isBlank() || !looksMojibake(value)) {
            return value;
        }
        try {
            byte[] bytes = value.getBytes(WINDOWS_1252);
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            return hasHangul(decoded) ? decoded : value;
        } catch (RuntimeException e) {
            return value;
        }
    }

    private static boolean looksMojibake(String value) {
        return value.indexOf('ì') >= 0
                || value.indexOf('ë') >= 0
                || value.indexOf('ê') >= 0
                || value.indexOf('í') >= 0
                || value.indexOf('Ã') >= 0
                || value.indexOf('Â') >= 0;
    }

    private static boolean hasHangul(String value) {
        if (value == null) {
            return false;
        }
        return value.chars()
                .anyMatch(codePoint -> Character.UnicodeBlock.of(codePoint)
                        == Character.UnicodeBlock.HANGUL_SYLLABLES);
    }

    private DiagnosisProfileSnapshot profileSnapshotFor(DiagnosisResult result) {
        if (result.getDiagnosisSessionId() == null) {
            return DiagnosisProfileSnapshot.empty();
        }
        Optional<DiagnosisSession> session =
                Optional.ofNullable(diagnosisSessionRepository.findById(result.getDiagnosisSessionId()))
                        .orElse(Optional.empty());
        return session
                .map(DiagnosisSession::getInputSnapshot)
                .map(diagnosisProfileScoringService::parse)
                .orElseGet(DiagnosisProfileSnapshot::empty);
    }

    private RecommendationCommentRequest.UserContext userContextFrom(DiagnosisProfileSnapshot profile) {
        if (profile == null) {
            return null;
        }
        return new RecommendationCommentRequest.UserContext(
                profile.grade(),
                profile.dreamJob(),
                profile.selectedSubjects().isEmpty() ? null : profile.selectedSubjects().get(0),
                profile.studyHours() == null ? null : Math.max(0, Math.min(168, (int) Math.round(profile.studyHours()))),
                profile.learningStyle(),
                null,
                null,
                null,
                null,
                null
        );
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

    private record MajorScoreDraft(
            Major major,
            Float competencyScore,
            Float tendencyScore,
            Float finalScore,
            Boolean failed,
            String recommendationReason
    ) {
    }

    private record AxisScore(String fieldName, String label, int score) {
    }
}
