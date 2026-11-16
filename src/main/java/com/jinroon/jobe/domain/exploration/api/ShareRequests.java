package com.jinroon.jobe.domain.exploration.api;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.*;
import lombok.Getter;

public final class ShareRequests {
    private ShareRequests(){}
    @Getter public static class Mutation extends ExplorationRequests.StrictRequest {
        @NotNull @PositiveOrZero private Long expectedShareRevision;
        @JsonSetter public void setExpectedShareRevision(Object value){if(value==null){expectedShareRevision=null;return;}if(!(value instanceof Integer||value instanceof Long||value instanceof java.math.BigInteger))throw new IllegalArgumentException("Share revision must be an integer");expectedShareRevision=Long.valueOf(value.toString());}
    }
    @Getter public static class Issue extends Mutation {
        private boolean includeNickname;
        @JsonSetter public void setIncludeNickname(Object value){if(!(value instanceof Boolean))throw new IllegalArgumentException("includeNickname must be a boolean");includeNickname=(Boolean)value;}
    }
}
