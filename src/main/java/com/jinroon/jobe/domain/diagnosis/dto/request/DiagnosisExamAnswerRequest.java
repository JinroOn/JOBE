package com.jinroon.jobe.domain.diagnosis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "객관식 답변 등록 요청 데이터")
public class DiagnosisExamAnswerRequest {

    @Schema(description = "진단 세션 ID", example = "1")
    private Long diagnosisSessionId;

    @Schema(description = "시험 문항 ID", example = "1")
    private Long examQuestionId;

    @Schema(description = "선택한 옵션 번호", example = "1")
    private Integer selectedOption;
}
