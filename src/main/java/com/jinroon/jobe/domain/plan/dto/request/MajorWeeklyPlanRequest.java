package com.jinroon.jobe.domain.plan.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "학습 계획 생성 및 수정 요청 데이터")
public class MajorWeeklyPlanRequest {

    @Schema(description = "진단 결과 ID", example = "1")
    private Long diagnosisResultId;

    @Schema(description = "전공별 점수(목표) ID", example = "1")
    private Long resultMajorScoreId;

    @Schema(description = "학습 계획 개요", example = "수리논리 및 정보통신 역량 향상을 위한 4주 완성 플랜")
    @Size(max = 10000)
    private String overview;

    @Schema(description = "계획 ID", example = "plan-a")
    @Size(max = 100)
    private String planId;

    @Schema(description = "버전 번호", example = "1")
    @Min(1)
    private Integer versionNo;

    @Schema(description = "상위 계획 ID", example = "1")
    private Long parentPlanId;

    @Schema(description = "대체 계획 여부", example = "false")
    private Boolean fallback;

    @Schema(description = "활성 버전 여부", example = "true")
    private Boolean activeVersion;
}
