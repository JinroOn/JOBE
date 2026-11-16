package com.jinroon.jobe.domain.exploration.api;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

public final class ContextRequests {
    private ContextRequests(){}
    @Getter @Setter public static class Prepare extends SupportRequests.Mutation {
        @NotNull @Pattern(regexp="CONSULTATION|PLAN") private String contextType;
        @Pattern(regexp="[!-~]{1,100}") private String targetMajorCode;
    }
}
