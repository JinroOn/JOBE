package com.jinroon.jobe.domain.exploration.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

public final class ExplorationRequests {
    private ExplorationRequests() {}

    public abstract static class StrictRequest {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown exploration request field");
        }
    }

    @Getter @Setter
    public static class Start extends StrictRequest {
        @NotNull @Pattern(regexp = "EXPLORATION_V1")
        private String diagnosisType;
    }

    @Getter
    public static class Mutation extends StrictRequest {
        @NotNull @PositiveOrZero
        private Long expectedRevision;

        @JsonSetter
        public void setExpectedRevision(Object value) {
            if (value == null) { expectedRevision = null; return; }
            if (!(value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger))
                throw new IllegalArgumentException("Revision must be a JSON integer");
            expectedRevision = Long.valueOf(value.toString());
        }
    }

    @Getter @Setter
    public static class Answer extends Mutation {
        @NotBlank @Size(max = 100)
        private String optionCode;
        @NotNull @Pattern(regexp = "[!-~]{1,128}")
        private String clientMutationId;
    }
}
