package com.jinroon.jobe.domain.result.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "진단 결과 생성 및 수정 요청 데이터")
public class DiagnosisResultRequest {

    @Schema(description = "진단 세션 ID", example = "1")
    private Long diagnosisSessionId;

    @Schema(description = "사용자 ID", example = "1")
    private Long userId;

    @Schema(description = "역량 평가 벡터 JSON", example = "{\"mathLogic\": 85, \"problemSolving\": 90}")
    @Size(max = 10000)
    private String competencyVector;

    @Schema(description = "성향 평가 벡터 JSON", example = "{\"artCreative\": 70, \"logicalInquiry\": 80}")
    @Size(max = 10000)
    private String tendencyVector;

    @Schema(description = "공유 토큰 (선택)", example = "custom_token_123")
    @Size(max = 128)
    private String shareToken;

    @Schema(description = "추천 전공 Top 3 JSON (선택)", example = "[1, 2, 3]")
    @Size(max = 10000)
    private String topMajorsJson;
}
