package com.jinroon.jobe.domain.plan.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "학습 계획 리스크 노트 생성 요청 데이터")
public class MajorWeeklyPlanRiskNoteRequest {

    @Schema(description = "학습 계획 ID", example = "1")
    private Long weeklyPlanId;

    @Schema(description = "리스크 노트 내용", example = "수리논리력이 목표 대비 57% 부족합니다. 수학 기초 학습 병행이 필요합니다.")
    @Size(max = 10000)
    private String note;
}
