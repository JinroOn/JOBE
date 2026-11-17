package com.jinroon.jobe.domain.exploration.candidate;

import java.util.List;

/** Public allowlist. Deliberately separate from private scoring/basis input and the first-screen card DTO. */
public final class CandidateViewDtos {
    private CandidateViewDtos() {}
    public record Reason(String kind, String reasonCode, String text) {}
    public record Card(String majorCode, String name, String groupCode, @com.fasterxml.jackson.annotation.JsonProperty("isFirstScreen") boolean isFirstScreen,
                       String evidenceLevel, List<Reason> reasons, String explanation,
                       String reasonAvailability, String reasonUnavailableCode, String basisGroupId) {}
    public record Group(String groupCode, String name, String labelAvailability, int candidateCount, int additionalCount) {}
    public record BasisGroup(String id, String kind, List<String> majorCodes, String message) {}
    public record GroupCount(String groupCode, int totalCount, int firstScreenCount, int additionalCount) {}
    public record Browsing(String viewVersion, String endpoint, int totalCount, int firstScreenCount,
                           int additionalCount, List<GroupCount> groupCounts) {}
    public record View(String viewVersion, String basisPolicyVersion, long resultId, String resultVersion,
                       String source, String status, String runMode, boolean testOnly,
                       int totalCount, int firstScreenCount, int additionalCount,
                       List<Group> groups, List<Card> items, List<BasisGroup> basisGroups) {}
}
