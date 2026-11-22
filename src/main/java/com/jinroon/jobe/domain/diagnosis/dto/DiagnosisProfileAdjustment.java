package com.jinroon.jobe.domain.diagnosis.dto;

import java.util.List;

public record DiagnosisProfileAdjustment(
        float bonus,
        List<String> reasons
) {
    public DiagnosisProfileAdjustment {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static DiagnosisProfileAdjustment neutral() {
        return new DiagnosisProfileAdjustment(0.0f, List.of());
    }

    public String conciseReason() {
        if (reasons.isEmpty()) {
            return null;
        }
        return String.join(" ", reasons);
    }
}
