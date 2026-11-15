package com.jinroon.jobe.domain.major.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import lombok.Getter;
import lombok.Setter;

/** Legacy catalog values only. Exploration axes, versions and approval states remain separate. */
@Getter
@Setter
public abstract class LegacyMajorScoresRequest {
    @DecimalMin("0.0") @DecimalMax("100.0") private Float reqMathLogic;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float reqProblemSolving;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float reqInfoTech;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float reqImplementation;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float reqSystemUnderstanding;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float reqDataAnalysis;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float reqCommunication;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float reqCollaboration;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float reqSelfManagement;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float tendLogicalInquiry;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float tendPracticalTech;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float tendArtCreative;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float tendSocialCooperation;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float tendLifeHealth;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float tendEducationGuide;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float tendTheoryAcademic;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float tendDataAnalytics;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float tendSystemOperation;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float thrMathLogic;
    @DecimalMin("0.0") @DecimalMax("100.0") private Float thrInfoTech;
    @JsonAnySetter
    public void rejectOtherFields(String name, Object value) {
        throw new IllegalArgumentException("Unsupported major field: " + name);
    }
}
