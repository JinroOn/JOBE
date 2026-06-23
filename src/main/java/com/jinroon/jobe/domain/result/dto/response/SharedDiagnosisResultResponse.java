package com.jinroon.jobe.domain.result.dto.response;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record SharedDiagnosisResultResponse(
        @JsonUnwrapped DiagnosisResult result,
        @Schema(description = "진단 결과 작성자 닉네임") String ownerNickname
) {
}
