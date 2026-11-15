package com.jinroon.jobe.domain.major.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Administrator-created general metadata; candidate profiles are versioned separately. */
@Getter
@Setter
public class MajorRequest {
    @NotBlank @Size(max=100) private String name;
    @NotBlank @Size(max=50) private String category;
    private String difficulty;
    @Size(max=20000) private String description;
    @Size(max=10000) private String careerPaths;

    @JsonAnySetter
    public void rejectOtherFields(String name, Object value) {
        throw new IllegalArgumentException("Unsupported major creation field: " + name);
    }
}
