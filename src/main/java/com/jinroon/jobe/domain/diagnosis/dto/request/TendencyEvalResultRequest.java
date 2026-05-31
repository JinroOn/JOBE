package com.jinroon.jobe.domain.diagnosis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "성향 평가 결과 등록 요청 데이터")
public class TendencyEvalResultRequest {

    @Schema(description = "진단 세션 ID", example = "1")
    private Long diagnosisSessionId;

    @Schema(description = "논리탐구 성향", example = "0.9")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float logicalInquiry;

    @Schema(description = "실용기술 성향", example = "0.8")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float practicalTech;

    @Schema(description = "예술창의 성향", example = "0.7")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float artCreative;

    @Schema(description = "사회협력 성향", example = "0.6")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float socialCooperation;

    @Schema(description = "생활건강 성향", example = "0.5")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float lifeHealth;

    @Schema(description = "교육지도 성향", example = "0.4")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float educationGuide;

    @Schema(description = "이론학술 성향", example = "0.3")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float theoryAcademic;

    @Schema(description = "데이터분석 성향", example = "0.2")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float dataAnalytics;

    @Schema(description = "시스템운영 성향", example = "0.1")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float systemOperation;
}
