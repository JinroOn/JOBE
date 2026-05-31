package com.jinroon.jobe.domain.diagnosis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "진단 세션 생성 및 수정 요청 데이터")
public class DiagnosisSessionRequest {

    @Schema(description = "사용자 ID", example = "1")
    private Long userId;

    @Schema(description = "세션 상태", example = "in_progress")
    private String status;

    @Schema(description = "현재 단계", example = "1")
    @Min(1)
    private Integer currentStep;

    @Schema(description = "시작 일시", example = "2026-05-30T15:00:00")
    private LocalDateTime startedAt;

    @Schema(description = "종료 일시", example = "2026-05-30T16:00:00")
    private LocalDateTime completedAt;

    @Schema(description = "입력 스냅샷 JSON", example = "{}")
    private String inputSnapshot;
}
