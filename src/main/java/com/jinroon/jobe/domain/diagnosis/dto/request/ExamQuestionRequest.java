package com.jinroon.jobe.domain.diagnosis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "시험 문항 생성 요청 데이터")
public class ExamQuestionRequest {

    @Schema(description = "역량 카테고리", example = "math_logic")
    private String competencyCategory;

    @Schema(description = "문항 내용", example = "다음 중 ...")
    @Size(max = 10000)
    private String questionText;

    @Schema(description = "보기 A", example = "A")
    @Size(max = 10000)
    private String optionA;

    @Schema(description = "보기 B", example = "B")
    @Size(max = 10000)
    private String optionB;

    @Schema(description = "보기 C", example = "C")
    @Size(max = 10000)
    private String optionC;

    @Schema(description = "보기 D", example = "D")
    @Size(max = 10000)
    private String optionD;

    @Schema(description = "정답", example = "A")
    @Pattern(regexp = "[ABCD]")
    private String correctAnswer;

    @Schema(description = "제한 시간(초)", example = "60")
    @Min(1)
    private Integer timeLimitSec;

    @Schema(description = "난이도(1~5)", example = "3")
    @Min(1)
    private Integer difficulty;

    @Schema(description = "수리논리 가중치", example = "1.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float wMathLogic;

    @Schema(description = "문제해결 가중치", example = "0.2")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float wProblemSolving;

    @Schema(description = "정보통신 가중치", example = "0.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float wInfoTech;

    @Schema(description = "구현 가중치", example = "0.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float wImplementation;

    @Schema(description = "시스템이해 가중치", example = "0.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float wSystemUnderstanding;

    @Schema(description = "데이터분석 가중치", example = "0.1")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float wDataAnalysis;

    @Schema(description = "의사소통 가중치", example = "0.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float wCommunication;

    @Schema(description = "협업 가중치", example = "0.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float wCollaboration;

    @Schema(description = "자기관리 가중치", example = "0.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float wSelfManagement;
}
