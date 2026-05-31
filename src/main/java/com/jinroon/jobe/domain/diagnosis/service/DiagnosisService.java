package com.jinroon.jobe.domain.diagnosis.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.domain.diagnosis.dto.response.InProgressSessionResponse;
import com.jinroon.jobe.domain.diagnosis.entity.*;
import com.jinroon.jobe.domain.diagnosis.enums.DiagnosisEnums.DiagnosisStatus;
import com.jinroon.jobe.domain.diagnosis.repository.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        DiagnosisSession session = diagnosisSessionRepository
                .findByUserIdAndStatus(userId, DiagnosisStatus.in_progress)
                .orElseThrow(() -> new CustomException(ErrorCode.DIAGNOSIS_SESSION_NOT_FOUND));
        List<DiagnosisExamAnswer> examAnswers = examAnswerRepository.findByDiagnosisSessionId(session.getId());
        List<DiagnosisEssayAnswer> essayAnswers = essayAnswerRepository.findByDiagnosisSessionId(session.getId());
        return new InProgressSessionResponse(session, examAnswers, essayAnswers);
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
        return examAnswerRepository.save(EntityFormMapper.create(DiagnosisExamAnswer.class, values));
    }

    @Transactional
    public DiagnosisExamAnswer createExamAnswerForUser(Map<String, Object> values, Long userId) {
        Long sessionId = ((Number) values.get("diagnosisSessionId")).longValue();
        getSessionForUser(sessionId, userId);
        return createExamAnswer(values);
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
        return createTendencyResult(values);
    }

    private static void requireOwner(Long ownerId, Long userId) {
        if (!Objects.equals(ownerId, userId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }
}
