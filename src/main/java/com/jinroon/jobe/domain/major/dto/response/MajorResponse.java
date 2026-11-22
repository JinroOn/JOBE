package com.jinroon.jobe.domain.major.dto.response;

import com.jinroon.jobe.domain.major.entity.Major;

/** Explicit legacy major catalog projection for existing screens. Not a new exploration result. */
public record MajorResponse(Long id, String name, String category, String difficulty,
                            String description, String careerPaths,
        Float reqMathLogic,
        Float reqProblemSolving,
        Float reqInfoTech,
        Float reqImplementation,
        Float reqSystemUnderstanding,
        Float reqDataAnalysis,
        Float reqCommunication,
        Float reqCollaboration,
        Float reqSelfManagement,
        Float tendLogicalInquiry,
        Float tendPracticalTech,
        Float tendArtCreative,
        Float tendSocialCooperation,
        Float tendLifeHealth,
        Float tendEducationGuide,
        Float tendTheoryAcademic,
        Float tendDataAnalytics,
        Float tendSystemOperation,
        Float thrMathLogic,
        Float thrInfoTech) {
    public static MajorResponse from(Major major) {
        return new MajorResponse(major.getId(), major.getName(), major.getCategory(),
                major.getDifficulty().name(), major.getDescription(), major.getCareerPaths(),
                major.getReqMathLogic(),
                major.getReqProblemSolving(),
                major.getReqInfoTech(),
                major.getReqImplementation(),
                major.getReqSystemUnderstanding(),
                major.getReqDataAnalysis(),
                major.getReqCommunication(),
                major.getReqCollaboration(),
                major.getReqSelfManagement(),
                major.getTendLogicalInquiry(),
                major.getTendPracticalTech(),
                major.getTendArtCreative(),
                major.getTendSocialCooperation(),
                major.getTendLifeHealth(),
                major.getTendEducationGuide(),
                major.getTendTheoryAcademic(),
                major.getTendDataAnalytics(),
                major.getTendSystemOperation(),
                major.getThrMathLogic(),
                major.getThrInfoTech());
    }
}
