package com.jinroon.jobe.domain.major.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Size;

public record MajorDescriptionRequest(@Size(max=20000) String description,
                                      @Size(max=10000) String careerPaths) {
    @JsonAnySetter
    public void rejectOtherFields(String name, Object value) {
        throw new IllegalArgumentException("Unsupported major update field: " + name);
    }
}
