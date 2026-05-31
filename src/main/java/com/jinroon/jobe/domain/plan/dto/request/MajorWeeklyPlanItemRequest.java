package com.jinroon.jobe.domain.plan.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "학습 계획 항목 생성 요청 데이터")
public class MajorWeeklyPlanItemRequest {

    @Schema(description = "학습 계획 ID", example = "1")
    private Long weeklyPlanId;

    @Schema(description = "주차 번호", example = "1")
    @Min(1)
    private Integer weekNo;

    @Schema(description = "학습 목표", example = "알고리즘 기초 학습")
    @Size(max = 300)
    private String goal;

    @Schema(description = "세부 학습 태스크 JSON", example = "[\"백준 단계별 풀기\", \"알고리즘 책 1-3장\"]")
    private String tasksJson;

    @Schema(description = "학습 자원 JSON", example = "[\"인프런 알고리즘 강의\", \"백준 온라인 저지\"]")
    private String resourcesJson;

    @Schema(description = "체크포인트", example = "정렬 알고리즘 3가지 직접 구현")
    @Size(max = 300)
    private String checkpoint;
}
