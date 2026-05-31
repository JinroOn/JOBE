package com.jinroon.jobe.domain.diagnosis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
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

    @Schema(description = "선택한 답", example = "A")
    @Pattern(regexp = "[ABCD]")
    private String selectedAnswer;

    @Schema(description = "정답 여부", example = "true")
    private Boolean correct;

    @Schema(description = "응답 시간(초)", example = "12")
    @Min(0)
    @Max(3600)
    private Integer responseSec;
}
