package com.jinroon.jobe.domain.consultation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "상담 세션 생성 및 수정 요청 데이터")
public class ConsultationSessionRequest {

    @Schema(description = "사용자 ID", example = "1")
    private Long userId;

    @Schema(description = "진단 결과 ID", example = "1")
    private Long diagnosisResultId;

    @Schema(description = "상담 제목", example = "데이터 사이언스 진로 상담")
    private String title;
}
