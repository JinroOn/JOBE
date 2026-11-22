package com.jinroon.jobe.domain.diagnosis.dto.response;

import com.jinroon.jobe.domain.diagnosis.entity.ExamQuestion;
import com.jinroon.jobe.domain.diagnosis.enums.DiagnosisEnums.CompetencyCategory;
import com.jinroon.jobe.domain.diagnosis.enums.DiagnosisEnums.QuestionType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 응시자에게 내려주는 문항 정보. 정답(correctAnswer)과 축별 채점 가중치(w*)는 포함하지 않는다.
 * 정오 판정과 점수 계산은 서버(DiagnosisScoringService)에서만 한다.
 */
@Schema(description = "시험 문항 (정답·가중치 제외)")
public record ExamQuestionResponse(
        Long id,
        CompetencyCategory competencyCategory,
        QuestionType questionType,
        String questionText,
        String optionA,
        String optionB,
        String optionC,
        String optionD,
        Integer timeLimitSec,
        Integer difficulty
) {
    public static ExamQuestionResponse from(ExamQuestion question) {
        return new ExamQuestionResponse(
                question.getId(),
                question.getCompetencyCategory(),
                question.getQuestionType(),
                question.getQuestionText(),
                question.getOptionA(),
                question.getOptionB(),
                question.getOptionC(),
                question.getOptionD(),
                question.getTimeLimitSec(),
                question.getDifficulty()
        );
    }
}
