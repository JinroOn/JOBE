package com.jinroon.jobe.domain.major.dto.response;

import com.jinroon.jobe.domain.major.entity.Major;

/** Public metadata only: legacy scoring vectors and thresholds are not public API fields. */
public record MajorResponse(Long id, String name, String category, String difficulty,
                            String description, String careerPaths) {
    public static MajorResponse from(Major major) {
        return new MajorResponse(major.getId(), major.getName(), major.getCategory(),
                major.getDifficulty().name(), major.getDescription(), major.getCareerPaths());
    }
}
