package com.jinroon.jobe.domain.diagnosis.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.domain.diagnosis.entity.CompetencyEvalResult;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisExamAnswer;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.diagnosis.entity.ExamQuestion;
import com.jinroon.jobe.domain.diagnosis.repository.CompetencyEvalResultRepository;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisExamAnswerRepository;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisSessionRepository;
import com.jinroon.jobe.domain.diagnosis.repository.ExamQuestionRepository;
import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DiagnosisScoringService {

    private final DiagnosisSessionRepository diagnosisSessionRepository;
    private final DiagnosisExamAnswerRepository examAnswerRepository;
    private final ExamQuestionRepository examQuestionRepository;
    private final CompetencyEvalResultRepository competencyEvalResultRepository;

    @Transactional
    public CompetencyEvalResult scoreCompetencyForUser(Long sessionId, Long userId) {
        DiagnosisSession session = get(diagnosisSessionRepository, sessionId, ErrorCode.DIAGNOSIS_SESSION_NOT_FOUND);
        requireOwner(session.getUserId(), userId);
        return scoreCompetency(session);
    }

    @Transactional
    public CompetencyEvalResult scoreCompetency(Long sessionId) {
        DiagnosisSession session = get(diagnosisSessionRepository, sessionId, ErrorCode.DIAGNOSIS_SESSION_NOT_FOUND);
        return scoreCompetency(session);
    }

    private CompetencyEvalResult scoreCompetency(DiagnosisSession session) {
        List<DiagnosisExamAnswer> answers = examAnswerRepository.findByDiagnosisSessionId(session.getId());
        if (answers.isEmpty()) {
            throw new CustomException(ErrorCode.DIAGNOSIS_ANSWER_NOT_FOUND);
        }

        List<Long> questionIds = answers.stream()
                .map(DiagnosisExamAnswer::getExamQuestionId)
                .distinct()
                .toList();
        Map<Long, ExamQuestion> questionsById = examQuestionRepository.findAllById(questionIds).stream()
                .collect(Collectors.toMap(ExamQuestion::getId, Function.identity()));
        if (questionsById.size() != questionIds.size()) {
            throw new CustomException(ErrorCode.DIAGNOSIS_QUESTION_NOT_FOUND);
        }

        ScoreAccumulator accumulator = new ScoreAccumulator();
        for (DiagnosisExamAnswer answer : answers) {
            ExamQuestion question = questionsById.get(answer.getExamQuestionId());
            boolean correct = isCorrect(answer, question);
            answer.markCorrect(correct);
            accumulator.add(question, correct, speedBonusRate(answer, question, correct));
        }

        ScoreResult score = accumulator.toScoreResult();
        return competencyEvalResultRepository.findByDiagnosisSessionId(session.getId())
                .map(existing -> {
                    existing.applyScores(
                            score.mathLogic(),
                            score.problemSolving(),
                            score.infoTech(),
                            score.implementation(),
                            score.systemUnderstanding(),
                            score.dataAnalysis(),
                            score.communication(),
                            score.collaboration(),
                            score.selfManagement()
                    );
                    return existing;
                })
                .orElseGet(() -> competencyEvalResultRepository.save(EntityFormMapper.create(
                        CompetencyEvalResult.class,
                        Map.of(
                                "diagnosisSessionId", session.getId(),
                                "mathLogic", score.mathLogic(),
                                "problemSolving", score.problemSolving(),
                                "infoTech", score.infoTech(),
                                "implementation", score.implementation(),
                                "systemUnderstanding", score.systemUnderstanding(),
                                "dataAnalysis", score.dataAnalysis(),
                                "communication", score.communication(),
                                "collaboration", score.collaboration(),
                                "selfManagement", score.selfManagement()
                        )
                )));
    }

    private boolean isCorrect(DiagnosisExamAnswer answer, ExamQuestion question) {
        if (answer.getSelectedAnswer() == null || question.getCorrectAnswer() == null) {
            return false;
        }
        return answer.getSelectedAnswer().trim().equalsIgnoreCase(question.getCorrectAnswer().trim());
    }

    private float speedBonusRate(DiagnosisExamAnswer answer, ExamQuestion question, boolean correct) {
        if (!correct || answer.getResponseSec() == null || question.getTimeLimitSec() == null) {
            return 0.0f;
        }

        int responseSec = answer.getResponseSec();
        int limitSec = question.getTimeLimitSec();
        if (responseSec < 3 || limitSec <= 0) {
            return 0.0f;
        }

        float ratio = responseSec / (float) limitSec;
        if (ratio <= 0.30f) {
            return 0.05f;
        }
        if (ratio <= 0.50f) {
            return 0.03f;
        }
        return 0.0f;
    }

    private void requireOwner(Long ownerId, Long userId) {
        if (!Objects.equals(ownerId, userId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }

    private static final class ScoreAccumulator {
        private final AxisWeights earned = new AxisWeights();
        private final AxisWeights max = new AxisWeights();

        private void add(ExamQuestion question, boolean correct, float speedBonusRate) {
            AxisWeights weights = AxisWeights.from(question);
            max.add(weights);
            if (correct) {
                earned.add(weights);
                earned.add(weights.scaled(speedBonusRate));
            }
        }

        private ScoreResult toScoreResult() {
            return new ScoreResult(
                    score(earned.mathLogic, max.mathLogic),
                    score(earned.problemSolving, max.problemSolving),
                    score(earned.infoTech, max.infoTech),
                    score(earned.implementation, max.implementation),
                    score(earned.systemUnderstanding, max.systemUnderstanding),
                    score(earned.dataAnalysis, max.dataAnalysis),
                    score(earned.communication, max.communication),
                    score(earned.collaboration, max.collaboration),
                    score(earned.selfManagement, max.selfManagement)
            );
        }

        private float score(float earnedWeight, float maxWeight) {
            if (maxWeight <= 0.0f) {
                return 0.0f;
            }
            int score = Math.round((earnedWeight / maxWeight) * 100.0f);
            return Math.max(0, Math.min(100, score));
        }
    }

    private static final class AxisWeights {
        private float mathLogic;
        private float problemSolving;
        private float infoTech;
        private float implementation;
        private float systemUnderstanding;
        private float dataAnalysis;
        private float communication;
        private float collaboration;
        private float selfManagement;

        private static AxisWeights from(ExamQuestion question) {
            AxisWeights weights = new AxisWeights();
            float difficultyWeight = difficultyWeight(question.getDifficulty());
            weights.mathLogic = safeWeight(question.getWMathLogic()) * difficultyWeight;
            weights.problemSolving = safeWeight(question.getWProblemSolving()) * difficultyWeight;
            weights.infoTech = safeWeight(question.getWInfoTech()) * difficultyWeight;
            weights.implementation = safeWeight(question.getWImplementation()) * difficultyWeight;
            weights.systemUnderstanding = safeWeight(question.getWSystemUnderstanding()) * difficultyWeight;
            weights.dataAnalysis = safeWeight(question.getWDataAnalysis()) * difficultyWeight;
            weights.communication = safeWeight(question.getWCommunication()) * difficultyWeight;
            weights.collaboration = safeWeight(question.getWCollaboration()) * difficultyWeight;
            weights.selfManagement = safeWeight(question.getWSelfManagement()) * difficultyWeight;
            return weights;
        }

        private void add(AxisWeights other) {
            mathLogic += other.mathLogic;
            problemSolving += other.problemSolving;
            infoTech += other.infoTech;
            implementation += other.implementation;
            systemUnderstanding += other.systemUnderstanding;
            dataAnalysis += other.dataAnalysis;
            communication += other.communication;
            collaboration += other.collaboration;
            selfManagement += other.selfManagement;
        }

        private AxisWeights scaled(float factor) {
            AxisWeights scaled = new AxisWeights();
            scaled.mathLogic = mathLogic * factor;
            scaled.problemSolving = problemSolving * factor;
            scaled.infoTech = infoTech * factor;
            scaled.implementation = implementation * factor;
            scaled.systemUnderstanding = systemUnderstanding * factor;
            scaled.dataAnalysis = dataAnalysis * factor;
            scaled.communication = communication * factor;
            scaled.collaboration = collaboration * factor;
            scaled.selfManagement = selfManagement * factor;
            return scaled;
        }

        private static float safeWeight(Float value) {
            return value == null ? 0.0f : Math.max(0.0f, value);
        }

        private static float difficultyWeight(Integer difficulty) {
            if (difficulty == null) {
                return 1.0f;
            }
            return switch (difficulty) {
                case 1 -> 0.6f;
                case 2 -> 0.8f;
                case 4 -> 1.2f;
                case 5 -> 1.4f;
                default -> 1.0f;
            };
        }
    }

    private record ScoreResult(
            Float mathLogic,
            Float problemSolving,
            Float infoTech,
            Float implementation,
            Float systemUnderstanding,
            Float dataAnalysis,
            Float communication,
            Float collaboration,
            Float selfManagement
    ) {
    }
}
