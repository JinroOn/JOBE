package com.jinroon.jobe.domain.diagnosis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "역량 평가 결과 등록 요청 데이터")
public class CompetencyEvalResultRequest {

    @Schema(description = "진단 세션 ID", example = "1")
    private Long diagnosisSessionId;

    @Schema(description = "수리논리 역량 (0.0 ~ 100.0)", example = "80.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float mathLogic;

    @Schema(description = "문제해결 역량 (0.0 ~ 100.0)", example = "75.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float problemSolving;

    @Schema(description = "정보통신 역량 (0.0 ~ 100.0)", example = "85.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float infoTech;

    @Schema(description = "구현 역량 (0.0 ~ 100.0)", example = "70.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float implementation;

    @Schema(description = "시스템이해 역량 (0.0 ~ 100.0)", example = "78.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float systemUnderstanding;

    @Schema(description = "데이터분석 역량 (0.0 ~ 100.0)", example = "90.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float dataAnalysis;

    @Schema(description = "의사소통 역량 (0.0 ~ 100.0)", example = "72.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float communication;

    @Schema(description = "협업 역량 (0.0 ~ 100.0)", example = "84.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float collaboration;

    @Schema(description = "자기관리 역량 (0.0 ~ 100.0)", example = "65.0")
    @DecimalMin("0.0")
    @DecimalMax("100.0")
    private Float selfManagement;
}
