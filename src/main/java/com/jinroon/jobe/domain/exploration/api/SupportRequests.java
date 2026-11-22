package com.jinroon.jobe.domain.exploration.api;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

public final class SupportRequests {
    private SupportRequests() {}
    @Getter
    public static class Mutation extends ExplorationRequests.StrictRequest {
        @NotNull @PositiveOrZero private Long expectedSupportRevision;
        @JsonSetter public void setExpectedSupportRevision(Object value) {
            if(value==null){expectedSupportRevision=null;return;}
            if(!(value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger))throw new IllegalArgumentException("Support revision must be a JSON integer");
            expectedSupportRevision=Long.valueOf(value.toString());
        }
    }
    @Getter @Setter
    public static class Select extends Mutation {
        @NotNull @Pattern(regexp="PLAN|ACTIVITY|BOTH") private String purpose;
        @Pattern(regexp="[!-~]{1,100}") private String targetMajorCode;
    }
    @Getter @Setter
    public static class Answer extends Mutation {
        @NotBlank @Size(max=100) private String optionCode;
        @NotNull @Pattern(regexp="[!-~]{1,128}") private String clientMutationId;
    }
}
