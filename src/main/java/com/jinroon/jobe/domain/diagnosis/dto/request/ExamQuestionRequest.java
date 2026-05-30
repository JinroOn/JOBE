package com.jinroon.jobe.domain.diagnosis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "시험 문항 생성 요청 데이터")
public class ExamQuestionRequest {

    @Schema(description = "역량 카테고리", example = "math_logic")
    private String competencyCategory;

    @Schema(description = "문항 내용", example = "다음 중 ...")
    private String questionText;

    @Schema(description = "문항 유형", example = "exam")
    private String questionType;
}
