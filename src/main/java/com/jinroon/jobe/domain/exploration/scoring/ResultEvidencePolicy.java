package com.jinroon.jobe.domain.exploration.scoring;

import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationResultStatus;
import java.util.*;
import static com.jinroon.jobe.domain.exploration.scoring.ExplorationScoreCalculator.AxisScore;

/** D06 overall evidence uses only axes actually used by displayed candidates. */
public final class ResultEvidencePolicy {
    public record Classification(ExplorationResultStatus status, List<String> reasonCodes) {}

    public Classification classify(int candidateCount, int validScreen, int minimumScreen, Collection<AxisScore> usedAxes) {
        if (candidateCount < 0 || validScreen < 0 || minimumScreen < 0) throw new IllegalArgumentException("Invalid evidence counts");
        TreeSet<String> reasons = new TreeSet<>();
        if (validScreen < minimumScreen) reasons.add("SCREEN_VALID_BELOW_MIN");
        if (usedAxes.stream().anyMatch(a -> a.validCount() == 1)) reasons.add("USED_INTEREST_AXIS_SINGLE_RESPONSE");
        if (usedAxes.stream().anyMatch(AxisScore::inconsistent)) reasons.add("USED_INTEREST_AXIS_INCONSISTENT");
        var status = reasons.isEmpty() ? ExplorationResultStatus.READY : ExplorationResultStatus.LOW_EVIDENCE;
        if (candidateCount == 0) { status = ExplorationResultStatus.NO_SUPPORTED_CANDIDATE; reasons.add("NO_SUPPORTED_CANDIDATE"); }
        return new Classification(status, List.copyOf(reasons));
    }
}
