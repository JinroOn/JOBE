package com.jinroon.jobe.domain.diagnosis.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.domain.diagnosis.dto.response.InProgressSessionResponse;
import com.jinroon.jobe.domain.diagnosis.entity.*;
import com.jinroon.jobe.domain.diagnosis.enums.DiagnosisEnums.DiagnosisStatus;
import com.jinroon.jobe.domain.consultation.repository.ConsultationLogRepository;
import com.jinroon.jobe.domain.consultation.repository.ConsultationSessionRepository;
import com.jinroon.jobe.domain.diagnosis.repository.*;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.domain.result.repository.ResultMajorScoreRepository;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanItemRepository;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanRepository;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanRiskNoteRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DiagnosisService {

    private final DiagnosisSessionRepository diagnosisSessionRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final DiagnosisExamAnswerRepository examAnswerRepository;
    private final DiagnosisEssayAnswerRepository essayAnswerRepository;
    private final CompetencyEvalResultRepository competencyEvalResultRepository;
    private final TendencyEvalResultRepository tendencyEvalResultRepository;
    private final DiagnosisResultRepository diagnosisResultRepository;
    private final ResultMajorScoreRepository resultMajorScoreRepository;
    private final MajorWeeklyPlanRepository majorWeeklyPlanRepository;
    private final MajorWeeklyPlanItemRepository majorWeeklyPlanItemRepository;
    private final MajorWeeklyPlanRiskNoteRepository majorWeeklyPlanRiskNoteRepository;
    private final ConsultationSessionRepository consultationSessionRepository;
    private final ConsultationLogRepository consultationLogRepository;

    public DiagnosisSession getSession(Long sessionId) {
        return get(diagnosisSessionRepository, sessionId, ErrorCode.DIAGNOSIS_SESSION_NOT_FOUND);
    }

    public DiagnosisSession getSessionForUser(Long sessionId, Long userId) {
        DiagnosisSession session = getSession(sessionId);
        requireOwner(session.getUserId(), userId);
        return session;
    }

    public List<DiagnosisSession> findSessionsByUser(Long userId) {
        return diagnosisSessionRepository.findByUserId(userId);
    }

    public InProgressSessionResponse getInProgressSession(Long userId) {
        Optional<DiagnosisSession> sessionOpt = diagnosisSessionRepository
                .findFirstByUserIdAndStatusOrderByIdDesc(userId, DiagnosisStatus.in_progress);
        if (sessionOpt.isEmpty()) return null;
        DiagnosisSession session = sessionOpt.get();
        List<DiagnosisExamAnswer> examAnswers = examAnswerRepository.findByDiagnosisSessionId(session.getId());
        List<DiagnosisEssayAnswer> essayAnswers = essayAnswerRepository.findByDiagnosisSessionId(session.getId());
        return new InProgressSessionResponse(session, examAnswers, essayAnswers);
    }

    @Transactional
    public void deleteSessionForUser(Long sessionId, Long userId) {
        getSessionForUser(sessionId, userId);

        // diagnosis_results 하위 전체 cascade 삭제
        diagnosisResultRepository.findByDiagnosisSessionId(sessionId).ifPresent(result -> {
            Long resultId = result.getId();

            // consultation_sessions → consultation_logs 삭제 (diagnosis_result_id FK)
            List<Long> consultationSessionIds = consultationSessionRepository
                    .findByDiagnosisResultId(resultId)
                    .stream().map(s -> s.getId()).toList();
            if (!consultationSessionIds.isEmpty()) {
                consultationLogRepository.deleteAllByConsultationSessionIdIn(consultationSessionIds);
                consultationSessionRepository.deleteAllById(consultationSessionIds);
            }

            List<Long> planIds = majorWeeklyPlanRepository.findByDiagnosisResultId(resultId)
                    .stream().map(p -> p.getId()).toList();
            if (!planIds.isEmpty()) {
                majorWeeklyPlanItemRepository.deleteAllByWeeklyPlanIdIn(planIds);
                majorWeeklyPlanRiskNoteRepository.deleteAllByWeeklyPlanIdIn(planIds);
                majorWeeklyPlanRepository.deleteAllById(planIds);
            }

            resultMajorScoreRepository.deleteAllByDiagnosisResultId(resultId);
            diagnosisResultRepository.delete(result);
        });

        // diagnosis_sessions 직속 하위 테이블 삭제
        examAnswerRepository.deleteAllByDiagnosisSessionId(sessionId);
        essayAnswerRepository.deleteAllByDiagnosisSessionId(sessionId);
        competencyEvalResultRepository.deleteByDiagnosisSessionId(sessionId);
        tendencyEvalResultRepository.deleteByDiagnosisSessionId(sessionId);
        diagnosisSessionRepository.deleteById(sessionId);
    }

    public List<ExamQuestion> findQuestions() {
        return examQuestionRepository.findAll();
    }

    public ExamQuestion getQuestion(Long questionId) {
        return get(examQuestionRepository, questionId, ErrorCode.DIAGNOSIS_QUESTION_NOT_FOUND);
    }

    public List<DiagnosisExamAnswer> findExamAnswers(Long sessionId) {
        return examAnswerRepository.findByDiagnosisSessionId(sessionId);
    }

    public List<DiagnosisExamAnswer> findExamAnswersForUser(Long sessionId, Long userId) {
        getSessionForUser(sessionId, userId);
        return findExamAnswers(sessionId);
    }

    public List<DiagnosisEssayAnswer> findEssayAnswers(Long sessionId) {
        return essayAnswerRepository.findByDiagnosisSessionId(sessionId);
    }

    public List<DiagnosisEssayAnswer> findEssayAnswersForUser(Long sessionId, Long userId) {
        getSessionForUser(sessionId, userId);
        return findEssayAnswers(sessionId);
    }

    public CompetencyEvalResult getCompetencyResult(Long sessionId) {
        return competencyEvalResultRepository.findByDiagnosisSessionId(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.DIAGNOSIS_COMPETENCY_RESULT_NOT_FOUND));
    }

    public CompetencyEvalResult getCompetencyResultForUser(Long sessionId, Long userId) {
        getSessionForUser(sessionId, userId);
        return getCompetencyResult(sessionId);
    }

    public TendencyEvalResult getTendencyResult(Long sessionId) {
        return tendencyEvalResultRepository.findByDiagnosisSessionId(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.DIAGNOSIS_TENDENCY_RESULT_NOT_FOUND));
    }

    public TendencyEvalResult getTendencyResultForUser(Long sessionId, Long userId) {
        getSessionForUser(sessionId, userId);
        return getTendencyResult(sessionId);
    }

    @Transactional
    public DiagnosisSession createSession(Map<String, Object> values) {
        return diagnosisSessionRepository.save(EntityFormMapper.create(DiagnosisSession.class, values));
    }

    @Transactional
    public DiagnosisSession createSessionForUser(Map<String, Object> values, Long userId) {
        values.put("userId", userId);
        return createSession(values);
    }

    @Transactional
    public DiagnosisSession updateSession(Long sessionId, Map<String, Object> values) {
        DiagnosisSession session = getSession(sessionId);
        EntityFormMapper.apply(session, values);
        return session;
    }

    @Transactional
    public DiagnosisSession updateSessionForUser(Long sessionId, Map<String, Object> values, Long userId) {
        DiagnosisSession session = getSessionForUser(sessionId, userId);
        values.remove("userId");
        EntityFormMapper.apply(session, values);
        return session;
    }

    @Transactional
    public ExamQuestion createQuestion(Map<String, Object> values) {
        return examQuestionRepository.save(EntityFormMapper.create(ExamQuestion.class, values));
    }

    @Transactional
    public DiagnosisExamAnswer createExamAnswer(Map<String, Object> values) {
        values.put("selectedAnswer", normalizeAnswer(stringValue(values.get("selectedAnswer"))));
        values.put("correct", false);
        return examAnswerRepository.save(EntityFormMapper.create(DiagnosisExamAnswer.class, values));
    }

    @Transactional
    public DiagnosisExamAnswer createExamAnswerForUser(Map<String, Object> values, Long userId) {
        Long sessionId = ((Number) values.get("diagnosisSessionId")).longValue();
        Long questionId = ((Number) values.get("examQuestionId")).longValue();
        getSessionForUser(sessionId, userId);
        ExamQuestion question = get(examQuestionRepository, questionId, ErrorCode.DIAGNOSIS_QUESTION_NOT_FOUND);
        if (question.isEssay()) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        return saveExamAnswer(values);
    }

    @Transactional
    public DiagnosisExamAnswer saveExamAnswer(Map<String, Object> values) {
        Long sessionId = ((Number) values.get("diagnosisSessionId")).longValue();
        Long questionId = ((Number) values.get("examQuestionId")).longValue();
        String selectedAnswer = normalizeAnswer(stringValue(values.get("selectedAnswer")));
        Integer responseSec = intValue(values.get("responseSec"));
        return examAnswerRepository.findByDiagnosisSessionIdAndExamQuestionId(sessionId, questionId)
                .map(existing -> {
                    existing.updateSubmission(selectedAnswer, responseSec);
                    return existing;
                })
                .orElseGet(() -> {
                    values.put("selectedAnswer", selectedAnswer);
                    values.put("correct", false);
                    values.put("responseSec", responseSec);
                    return createExamAnswer(values);
                });
    }

    @Transactional
    public DiagnosisEssayAnswer createEssayAnswer(Map<String, Object> values) {
        return essayAnswerRepository.save(EntityFormMapper.create(DiagnosisEssayAnswer.class, values));
    }

    @Transactional
    public DiagnosisEssayAnswer createEssayAnswerForUser(Map<String, Object> values, Long userId) {
        Long sessionId = ((Number) values.get("diagnosisSessionId")).longValue();
        getSessionForUser(sessionId, userId);
        return createEssayAnswer(values);
    }

    @Transactional
    public CompetencyEvalResult createCompetencyResult(Map<String, Object> values) {
        return competencyEvalResultRepository.save(EntityFormMapper.create(CompetencyEvalResult.class, values));
    }

    @Transactional
    public CompetencyEvalResult createCompetencyResultForUser(Map<String, Object> values, Long userId) {
        Long sessionId = ((Number) values.get("diagnosisSessionId")).longValue();
        getSessionForUser(sessionId, userId);
        return createCompetencyResult(values);
    }

    @Transactional
    public TendencyEvalResult createTendencyResult(Map<String, Object> values) {
        return tendencyEvalResultRepository.save(EntityFormMapper.create(TendencyEvalResult.class, values));
    }

    @Transactional
    public TendencyEvalResult createTendencyResultForUser(Map<String, Object> values, Long userId) {
        Long sessionId = ((Number) values.get("diagnosisSessionId")).longValue();
        getSessionForUser(sessionId, userId);
        return saveTendencyResult(values);
    }

    @Transactional
    public TendencyEvalResult saveTendencyResult(Map<String, Object> values) {
        Long sessionId = ((Number) values.get("diagnosisSessionId")).longValue();
        return tendencyEvalResultRepository.findByDiagnosisSessionId(sessionId)
                .map(existing -> {
                    existing.updateScores(
                            floatValue(values.get("logicalInquiry")),
                            floatValue(values.get("practicalTech")),
                            floatValue(values.get("artCreative")),
                            floatValue(values.get("socialCooperation")),
                            floatValue(values.get("lifeHealth")),
                            floatValue(values.get("educationGuide")),
                            floatValue(values.get("theoryAcademic")),
                            floatValue(values.get("dataAnalytics")),
                            floatValue(values.get("systemOperation"))
                    );
                    return existing;
                })
                .orElseGet(() -> createTendencyResult(values));
    }

    private static void requireOwner(Long ownerId, Long userId) {
        if (!Objects.equals(ownerId, userId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }

    private static Float floatValue(Object value) {
        return value instanceof Number number ? number.floatValue() : null;
    }

    private static Integer intValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string : null;
    }

    private static String normalizeAnswer(String selectedAnswer) {
        if (selectedAnswer == null || selectedAnswer.isBlank()) {
            return null;
        }
        return selectedAnswer.trim().toUpperCase();
    }
}
