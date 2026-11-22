package com.jinroon.jobe.domain.diagnosis.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "서술형 답변 등록 요청 데이터")
public class DiagnosisEssayAnswerRequest {

    @Schema(description = "진단 세션 ID", example = "1")
    private Long diagnosisSessionId;

    @Schema(description = "질문 번호", example = "1")
    @Min(1)
    private Integer questionNo;

    @Schema(description = "답변 내용", example = "데이터 사이언티스트가 되고 싶습니다.")
    @Size(max = 10000)
    private String answerText;
}
