package com.jinroon.jobe.domain.consultation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "AI consultation message request")
public record ConsultationMessageRequest(
        @Schema(description = "User message", example = "내가 따면 좋을 자격증을 추천해줘")
        @NotBlank
        @Size(max = 10000)
        String content
) {
}
