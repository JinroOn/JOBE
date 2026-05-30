package com.jinroon.jobe.domain.consultation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "상담 로그 추가 요청 데이터")
public class ConsultationLogRequest {

    @Schema(description = "상담 세션 ID", example = "1")
    private Long consultationSessionId;

    @Schema(description = "작성자 역할 (user 또는 advisor)", example = "user")
    private String role;

    @Schema(description = "대화 내용", example = "비전공자인데 데이터 분석가로 전직할 수 있을까요?")
    private String content;
}
