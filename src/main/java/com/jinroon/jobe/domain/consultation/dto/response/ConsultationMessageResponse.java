package com.jinroon.jobe.domain.consultation.dto.response;

import com.jinroon.jobe.domain.consultation.entity.ConsultationLog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "AI consultation message response")
public record ConsultationMessageResponse(
        ConsultationLog userLog,
        ConsultationLog assistantLog,
        ContextUsed contextUsed
) {
    public record ContextUsed(
            Long diagnosisResultId,
            boolean usedLatestDiagnosisResult,
            int historyMessageCount,
            List<String> topMajorNames,
            List<String> weaknessFocus
    ) {
    }
}
