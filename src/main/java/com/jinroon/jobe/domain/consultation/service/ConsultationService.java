package com.jinroon.jobe.domain.consultation.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.client.AiServiceClient;
import com.jinroon.jobe.global.client.dto.request.DiagnosisProfileContext;
import com.jinroon.jobe.global.client.dto.request.ConsultationChatRequest;
import com.jinroon.jobe.global.client.dto.response.ConsultationChatResponse;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.domain.consultation.entity.ConsultationLog;
import com.jinroon.jobe.domain.consultation.entity.ConsultationSession;
import com.jinroon.jobe.domain.consultation.repository.ConsultationLogRepository;
import com.jinroon.jobe.domain.consultation.repository.ConsultationSessionRepository;
import com.jinroon.jobe.domain.consultation.dto.request.ConsultationMessageRequest;
import com.jinroon.jobe.domain.consultation.dto.response.ConsultationMessageResponse;
import com.jinroon.jobe.domain.diagnosis.dto.DiagnosisProfileSnapshot;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisSessionRepository;
import com.jinroon.jobe.domain.diagnosis.service.DiagnosisProfileScoringService;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlan;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlanItem;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanItemRepository;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanRepository;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.entity.ResultMajorScore;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.domain.result.repository.ResultMajorScoreRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ConsultationService {

    private final ConsultationSessionRepository consultationSessionRepository;
    private final ConsultationLogRepository consultationLogRepository;
    private final DiagnosisResultRepository diagnosisResultRepository;
    private final DiagnosisSessionRepository diagnosisSessionRepository;
    private final ResultMajorScoreRepository resultMajorScoreRepository;
    private final MajorRepository majorRepository;
    private final MajorWeeklyPlanRepository planRepository;
    private final MajorWeeklyPlanItemRepository planItemRepository;
    private final DiagnosisProfileScoringService diagnosisProfileScoringService;
    private final AiServiceClient aiServiceClient;

    public List<ConsultationSession> findSessionsByUser(Long userId) {
        return consultationSessionRepository.findByUserId(userId);
    }

    public ConsultationSession getSession(Long sessionId) {
        return get(consultationSessionRepository, sessionId, ErrorCode.CONSULTATION_SESSION_NOT_FOUND);
    }

    public ConsultationSession getSessionForUser(Long sessionId, Long userId) {
        ConsultationSession session = getSession(sessionId);
        requireOwner(session.getUserId(), userId);
        return session;
    }

    public List<ConsultationLog> findLogs(Long sessionId) {
        return consultationLogRepository.findByConsultationSessionIdOrderByCreatedAtAsc(sessionId);
    }

    public List<ConsultationLog> findLogsForUser(Long sessionId, Long userId) {
        getSessionForUser(sessionId, userId);
        return findLogs(sessionId);
    }

    public ConsultationLog getLog(Long logId) {
        return get(consultationLogRepository, logId, ErrorCode.CONSULTATION_LOG_NOT_FOUND);
    }

    public ConsultationLog getLogForUser(Long logId, Long userId) {
        ConsultationLog log = getLog(logId);
        getSessionForUser(log.getConsultationSessionId(), userId);
        return log;
    }

    @Transactional
    public ConsultationSession createSession(Map<String, Object> values) {
        return consultationSessionRepository.save(EntityFormMapper.create(ConsultationSession.class, values));
    }

    @Transactional
    public ConsultationSession createSessionForUser(Map<String, Object> values, Long userId) {
        values.put("userId", userId);
        return createSession(values);
    }

    @Transactional
    public ConsultationSession updateSession(Long sessionId, Map<String, Object> values) {
        ConsultationSession session = getSession(sessionId);
        EntityFormMapper.apply(session, values);
        return session;
    }

    @Transactional
    public ConsultationSession updateSessionForUser(Long sessionId, Map<String, Object> values, Long userId) {
        ConsultationSession session = getSessionForUser(sessionId, userId);
        values.remove("userId");
        EntityFormMapper.apply(session, values);
        return session;
    }

    @Transactional
    public ConsultationSession endSession(Long sessionId) {
        ConsultationSession session = getSession(sessionId);
        if (!session.isEnded()) {
            session.end();
        }
        return session;
    }

    @Transactional
    public ConsultationSession endSessionForUser(Long sessionId, Long userId) {
        getSessionForUser(sessionId, userId);
        return endSession(sessionId);
    }

    @Transactional
    public ConsultationLog createLog(Map<String, Object> values) {
        Long sessionId = ((Number) values.get("consultationSessionId")).longValue();
        ConsultationSession session = getSession(sessionId);
        if (session.isEnded()) {
            throw new CustomException(ErrorCode.CONSULTATION_SESSION_ALREADY_ENDED);
        }
        return consultationLogRepository.save(EntityFormMapper.create(ConsultationLog.class, values));
    }

    @Transactional
    public ConsultationLog createLogForUser(Map<String, Object> values, Long userId) {
        Long sessionId = ((Number) values.get("consultationSessionId")).longValue();
        getSessionForUser(sessionId, userId);
        return createLog(values);
    }

    @Transactional
    public ConsultationMessageResponse createMessageWithAiReplyForUser(
            Long sessionId,
            ConsultationMessageRequest request,
            Long userId
    ) {
        ConsultationSession session = getSessionForUser(sessionId, userId);
        if (session.isEnded()) {
            throw new CustomException(ErrorCode.CONSULTATION_SESSION_ALREADY_ENDED);
        }

        ConsultationLog userLog = saveLog(sessionId, "user", request.content());
        List<ConsultationLog> history = recentLogs(findLogs(sessionId), 20);
        ConsultationContext context = buildConsultationContext(session, userId);
        ConsultationChatResponse aiResponse = aiServiceClient.getConsultationChat(
                buildAiRequest(session, userId, request.content(), history, context)
        );

        String assistantContent = aiResponse != null && aiResponse.content() != null && !aiResponse.content().isBlank()
                ? aiResponse.content()
                : jinroonFallbackAssistantMessage(context);
        ConsultationLog assistantLog = saveLog(sessionId, "assistant", assistantContent);

        return new ConsultationMessageResponse(
                userLog,
                assistantLog,
                new ConsultationMessageResponse.ContextUsed(
                        context.diagnosisResultId(),
                        context.usedLatestDiagnosisResult(),
                        history.size(),
                        context.topMajorNames(),
                        context.weaknessFocus()
                )
        );
    }

    private static void requireOwner(Long ownerId, Long userId) {
        if (!Objects.equals(ownerId, userId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }

    private ConsultationLog saveLog(Long sessionId, String role, String content) {
        return consultationLogRepository.save(EntityFormMapper.create(ConsultationLog.class, Map.of(
                "consultationSessionId", sessionId,
                "role", role,
                "content", content
        )));
    }

    private ConsultationChatRequest buildAiRequest(
            ConsultationSession session,
            Long userId,
            String userMessage,
            List<ConsultationLog> history,
            ConsultationContext context
    ) {
        return new ConsultationChatRequest(
                session.getId(),
                userId,
                userMessage,
                history.stream()
                        .map(log -> new ConsultationChatRequest.HistoryMessage(
                                log.getRole().name(),
                                log.getContent()
                        ))
                        .toList(),
                context.diagnosisContext() != null,
                context.diagnosisContext()
        );
    }

    private ConsultationContext buildConsultationContext(ConsultationSession session, Long userId) {
        Optional<DiagnosisResult> resultOpt;
        boolean usedLatest = false;
        if (session.getDiagnosisResultId() != null) {
            resultOpt = diagnosisResultRepository.findById(session.getDiagnosisResultId());
            resultOpt.ifPresent(result -> requireOwner(result.getUserId(), userId));
        } else {
            resultOpt = diagnosisResultRepository.findTopByUserIdOrderByCreatedAtDesc(userId);
            usedLatest = resultOpt.isPresent();
        }
        if (resultOpt.isEmpty()) {
            return new ConsultationContext(null, usedLatest, List.of(), List.of(), null);
        }

        DiagnosisResult result = resultOpt.get();
        List<ResultMajorScore> scores = resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(result.getId())
                .stream()
                .limit(5)
                .toList();
        Map<Long, Major> majorMap = majorRepository.findAllById(
                        scores.stream().map(ResultMajorScore::getMajorId).toList()
                )
                .stream()
                .collect(Collectors.toMap(Major::getId, major -> major));
        List<ConsultationChatRequest.TopMajor> topMajors = scores.stream()
                .map(score -> toTopMajor(score, majorMap.get(score.getMajorId())))
                .toList();
        List<String> topMajorNames = topMajors.stream()
                .map(ConsultationChatRequest.TopMajor::majorName)
                .filter(Objects::nonNull)
                .toList();
        List<String> weaknessFocus = splitWeaknessFocus(result.getWeaknessFocus());
        List<ConsultationChatRequest.PlanContext> plans = planRepository.findByDiagnosisResultId(result.getId())
                .stream()
                .filter(plan -> Boolean.TRUE.equals(plan.getActiveVersion()))
                .sorted(Comparator.comparing(MajorWeeklyPlan::getVersionNo, Comparator.nullsLast(Integer::compareTo)).reversed())
                .limit(3)
                .map(this::toPlanContext)
                .toList();

        ConsultationChatRequest.DiagnosisContext diagnosisContext = new ConsultationChatRequest.DiagnosisContext(
                result.getId(),
                usedLatest,
                result.getCompetencyVector(),
                result.getTendencyVector(),
                result.getAiComment(),
                weaknessFocus,
                topMajors,
                plans,
                DiagnosisProfileContext.from(profileSnapshotFor(result))
        );
        return new ConsultationContext(result.getId(), usedLatest, topMajorNames, weaknessFocus, diagnosisContext);
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

    private ConsultationChatRequest.TopMajor toTopMajor(ResultMajorScore score, Major major) {
        return new ConsultationChatRequest.TopMajor(
                score.getMajorId(),
                major != null ? major.getName() : null,
                score.getRank(),
                score.getFinalScore(),
                score.getCompetencyScore(),
                score.getTendencyScore(),
                score.getFailed(),
                score.getStrengths(),
                score.getWeaknesses(),
                score.getRecommendationReason(),
                major != null ? toMajorContext(major) : null
        );
    }

    private ConsultationChatRequest.MajorContext toMajorContext(Major major) {
        Map<String, Float> requiredCompetencies = new HashMap<>();
        requiredCompetencies.put("mathLogicalScore", major.getReqMathLogic());
        requiredCompetencies.put("problemSolvingScore", major.getReqProblemSolving());
        requiredCompetencies.put("infoTechUtilizationScore", major.getReqInfoTech());
        requiredCompetencies.put("softwareImplementationScore", major.getReqImplementation());
        requiredCompetencies.put("systemUnderstandingScore", major.getReqSystemUnderstanding());
        requiredCompetencies.put("dataAnalysisScore", major.getReqDataAnalysis());
        requiredCompetencies.put("communicationScore", major.getReqCommunication());
        requiredCompetencies.put("collaborationScore", major.getReqCollaboration());
        requiredCompetencies.put("selfManagementScore", major.getReqSelfManagement());
        return new ConsultationChatRequest.MajorContext(
                major.getCategory(),
                major.getDescription(),
                major.getCareerPaths(),
                requiredCompetencies
        );
    }

    private ConsultationChatRequest.PlanContext toPlanContext(MajorWeeklyPlan plan) {
        List<ConsultationChatRequest.PlanItem> items = planItemRepository
                .findByWeeklyPlanIdOrderByWeekNoAsc(plan.getId())
                .stream()
                .map(this::toPlanItem)
                .toList();
        return new ConsultationChatRequest.PlanContext(
                plan.getId(),
                plan.getResultMajorScoreId(),
                plan.getOverview(),
                plan.getActiveVersion(),
                items
        );
    }

    private ConsultationChatRequest.PlanItem toPlanItem(MajorWeeklyPlanItem item) {
        return new ConsultationChatRequest.PlanItem(
                item.getWeekNo(),
                item.getGoal(),
                item.getTasksJson(),
                item.getResourcesJson(),
                item.getCheckpoint(),
                item.getIsCompleted()
        );
    }

    private List<ConsultationLog> recentLogs(List<ConsultationLog> logs, int limit) {
        if (logs.size() <= limit) {
            return logs;
        }
        return logs.subList(logs.size() - limit, logs.size());
    }

    private List<String> splitWeaknessFocus(String weaknessFocus) {
        if (weaknessFocus == null || weaknessFocus.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(weaknessFocus.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private String fallbackAssistantMessage(ConsultationContext context) {
        if (context.diagnosisResultId() == null && context.topMajorNames().isEmpty()) {
            return "아직 참고할 진단 결과나 충분한 상담 기록이 없습니다. 관심 전공, 목표 직무, 좋아하는 과목, 현재 고민을 알려주면 그 내용을 바탕으로 상담을 이어갈 수 있습니다.";
        }
        return "AI 상담 응답을 생성하는 중 문제가 발생했습니다. 저장된 진단 결과와 상담 기록은 유지되었으니 잠시 후 다시 질문해 주세요.";
    }

    private String jinroonFallbackAssistantMessage(ConsultationContext context) {
        if (context.diagnosisResultId() == null && context.topMajorNames().isEmpty()) {
            return "아직 진로온 전공 추천 결과가 없어 개인 맞춤형 답변을 바로 드리기는 어렵습니다. "
                    + "먼저 진로온의 전공 추천 진단을 진행하면, 추천 전공과 강점/보완점을 바탕으로 더 적합한 방향을 안내해드릴 수 있어요. "
                    + "진단 전이라면 관심 분야, 목표 직무, 현재 고민 중 하나를 알려주세요.";
        }
        return "AI 상담 응답을 생성하는 중 문제가 발생했습니다. "
                + "저장된 진로온 진단 결과와 상담 기록은 유지되어 있으니, 질문을 다시 보내주시면 해당 맥락을 기준으로 답변하겠습니다.";
    }

    private record ConsultationContext(
            Long diagnosisResultId,
            boolean usedLatestDiagnosisResult,
            List<String> topMajorNames,
            List<String> weaknessFocus,
            ConsultationChatRequest.DiagnosisContext diagnosisContext
    ) {
    }
}
