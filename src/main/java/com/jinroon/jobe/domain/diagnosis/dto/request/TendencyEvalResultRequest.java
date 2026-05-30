package com.jinroon.jobe.domain.diagnosis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "성향 평가 결과 등록 요청 데이터")
public class TendencyEvalResultRequest {

    @Schema(description = "진단 세션 ID", example = "1")
    private Long diagnosisSessionId;

    @Schema(description = "학습 스타일", example = "theory")
    private String learningStyle;

    @Schema(description = "탐구 성향", example = "7.0")
    private Float explorationTendency;

    @Schema(description = "진로 목표", example = "AI 데이터 사이언티스트")
    private String careerGoalText;
}
