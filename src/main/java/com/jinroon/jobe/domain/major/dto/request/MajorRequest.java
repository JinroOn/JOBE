package com.jinroon.jobe.domain.major.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Administrator-only legacy catalog contract; does not modify exploration profiles. */
@Getter
@Setter
public class MajorRequest extends LegacyMajorScoresRequest {
    @NotBlank @Size(min=1,max=100) private String name;
    @NotBlank @Size(min=1,max=50) private String category;
    @Pattern(regexp="low|mid|high") private String difficulty;
    @Size(max=20000) private String description;
    @Size(max=10000) private String careerPaths;
}
