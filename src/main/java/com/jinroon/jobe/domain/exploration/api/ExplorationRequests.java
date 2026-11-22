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

    @Getter
    public static class Presentation extends Mutation {
        @NotNull @jakarta.validation.constraints.Positive
        private Long checkpointId;
        @NotNull private java.util.List<String> unexpected;
        @NotNull private java.util.List<String> unfamiliar;

        @JsonSetter public void setCheckpointId(Object value) {
            if (value == null) { checkpointId = null; return; }
            if (!(value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger))
                throw new IllegalArgumentException("Checkpoint must be a JSON integer");
            checkpointId = Long.valueOf(value.toString());
        }
        @JsonSetter public void setUnexpected(Object value) { unexpected = codes(value); }
        @JsonSetter public void setUnfamiliar(Object value) { unfamiliar = codes(value); }
        private static java.util.List<String> codes(Object value) {
            if (!(value instanceof java.util.List<?> list) || list.size() > 7
                    || list.stream().anyMatch(v -> !(v instanceof String s) || s.isBlank() || s.length() > 100)
                    || new java.util.HashSet<>(list).size() != list.size())
                throw new IllegalArgumentException("Expected up to seven distinct major codes");
            return list.stream().map(String.class::cast).sorted().toList();
        }
    }
}
