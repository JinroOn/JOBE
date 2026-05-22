package com.jinroon.jobe.domain.diagnosis.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.domain.diagnosis.entity.*;
import com.jinroon.jobe.domain.diagnosis.repository.*;
import java.util.List;
import java.util.Map;
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

    public List<DiagnosisSession> findSessionsByUser(Long userId) {
        return diagnosisSessionRepository.findByUserId(userId);
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

    public List<DiagnosisEssayAnswer> findEssayAnswers(Long sessionId) {
        return essayAnswerRepository.findByDiagnosisSessionId(sessionId);
    }

    public CompetencyEvalResult getCompetencyResult(Long sessionId) {
        return competencyEvalResultRepository.findByDiagnosisSessionId(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.DIAGNOSIS_COMPETENCY_RESULT_NOT_FOUND));
    }

    public TendencyEvalResult getTendencyResult(Long sessionId) {
        return tendencyEvalResultRepository.findByDiagnosisSessionId(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.DIAGNOSIS_TENDENCY_RESULT_NOT_FOUND));
    }

    @Transactional
    public DiagnosisSession createSession(Map<String, Object> values) {
        return diagnosisSessionRepository.save(EntityFormMapper.create(DiagnosisSession.class, values));
    }

    @Transactional
    public DiagnosisSession updateSession(Long sessionId, Map<String, Object> values) {
        DiagnosisSession session = getSession(sessionId);
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
    public DiagnosisEssayAnswer createEssayAnswer(Map<String, Object> values) {
        return essayAnswerRepository.save(EntityFormMapper.create(DiagnosisEssayAnswer.class, values));
    }

    @Transactional
    public CompetencyEvalResult createCompetencyResult(Map<String, Object> values) {
        return competencyEvalResultRepository.save(EntityFormMapper.create(CompetencyEvalResult.class, values));
    }

    @Transactional
    public TendencyEvalResult createTendencyResult(Map<String, Object> values) {
        return tendencyEvalResultRepository.save(EntityFormMapper.create(TendencyEvalResult.class, values));
    }
}
