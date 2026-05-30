package com.jinroon.jobe.domain.result.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "전공별 점수 생성 및 수정 요청 데이터")
public class ResultMajorScoreRequest {

    @Schema(description = "진단 결과 ID", example = "1")
    private Long diagnosisResultId;

    @Schema(description = "전공 ID", example = "1")
    private Long majorId;

    @Schema(description = "성향 적합도 점수", example = "85.0")
    private Float tendencyScore;

    @Schema(description = "역량 적합도 점수", example = "90.0")
    private Float competencyScore;

    @Schema(description = "최종 산출 점수", example = "87.5")
    private Float finalScore;

    @Schema(description = "순위", example = "1")
    private Integer rank;

    @Schema(description = "불합격 여부(과락)", example = "false")
    private Boolean failed;
}
