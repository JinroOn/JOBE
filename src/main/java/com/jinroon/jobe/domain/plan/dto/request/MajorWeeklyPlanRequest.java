package com.jinroon.jobe.domain.plan.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
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
    private String overview;

    @Schema(description = "총 소요 기간 (주)", example = "4")
    private Integer durationWeeks;
}
