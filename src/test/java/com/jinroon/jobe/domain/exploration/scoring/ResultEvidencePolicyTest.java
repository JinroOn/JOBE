package com.jinroon.jobe.domain.exploration.scoring;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import static com.jinroon.jobe.domain.exploration.scoring.ExplorationScoreCalculator.AxisScore;

class ResultEvidencePolicyTest {
    static Stream<com.fasterxml.jackson.databind.JsonNode> classificationCases() throws Exception {
        List<com.fasterxml.jackson.databind.JsonNode> cases = new ArrayList<>();
        new ObjectMapper().readTree(Files.readString(Path.of("datasets/exploration-validation/completion-cases.json"))).path("cases")
                .forEach(c -> { if (!c.path("expectedStatus").isNull()) cases.add(c); });
        return cases.stream();
    }
    @ParameterizedTest @MethodSource("classificationCases")
    void matchesD06ClassificationCases(com.fasterxml.jackson.databind.JsonNode c) {
        List<AxisScore> used = new ArrayList<>();
        c.path("usedAxes").forEach(a -> used.add(new AxisScore(75.0, a.path("count").asInt(), !a.path("difference").isNull() && a.path("difference").asDouble() >= 50)));
        var actual = new ResultEvidencePolicy().classify(c.path("candidateCount").asInt(), c.path("screenValid").asInt(), 12, used);
        assertThat(actual.status().name()).isEqualTo(c.path("expectedStatus").asText());
        assertThat(actual.reasonCodes()).isSorted().doesNotHaveDuplicates();
    }
    @Test void insufficientUnusedAxesDoNotDowngradeTheWholeResult() {
        var result = new ResultEvidencePolicy().classify(2, 14, 12, List.of(new AxisScore(75.0,2,false)));
        assertThat(result.status().name()).isEqualTo("READY");
        assertThat(result.reasonCodes()).isEmpty();
    }
}
