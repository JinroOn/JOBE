package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.context.MajorExplanationEvidence;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MajorExplanationEvidenceTest extends CompletedExplorationTestSupport {
    @Autowired MajorExplanationEvidence evidence;

    @Test
    void selectsOnlySavedCandidateSignalsAndKeepsNeutralAnswersNeutral() throws Exception {
        long resultId = completedResult(Map.of("INT_MED", 100, "INT_HUM", 50));
        var snapshot = resultSnapshot(resultId);
        var candidate = StreamSupport.stream(snapshot.path("rankedCandidates").spliterator(), false)
                .filter(row -> row.path("majorCode").asText().equals("MAJOR_ROW_6")).findFirst().orElseThrow();
        var prepared = evidence.prepare(OWNER, resultId, "MAJOR_ROW_6");

        assertThat(prepared.majorName()).isEqualTo("문예창작학과");
        assertThat(prepared.resultHash()).matches("[a-f0-9]{64}");
        assertThat(prepared.signals()).isNotEmpty().hasSizeLessThanOrEqualTo(6);
        assertThat(prepared.signals()).allSatisfy(signal -> {
            String usedField = switch (signal.kind()) {
                case INTEREST -> "usedInterestAxes";
                case ACTIVITY -> "usedActivityAxes";
                case LEARNING -> "usedLearningSets";
            };
            assertThat(StreamSupport.stream(candidate.path(usedField).spliterator(), false)
                    .map(JsonNode::asText).toList()).contains(signal.metricCode());
            assertThat(signal.questionText()).isNotBlank();
            assertThat(signal.answerText()).isNotBlank();
            assertThat(signal.validResponseCount()).isPositive();
        });
        assertThat(prepared.signals()).anySatisfy(signal -> {
            assertThat(signal.metricCode()).isEqualTo("INT_MED");
            assertThat(signal.direction()).isEqualTo(MajorExplanationEvidence.ResponseDirection.POSITIVE);
        });
        assertThat(prepared.signals()).anySatisfy(signal -> {
            assertThat(signal.metricCode()).isEqualTo("INT_HUM");
            assertThat(signal.direction()).isEqualTo(MajorExplanationEvidence.ResponseDirection.NEUTRAL);
        });
        assertThat(evidence.prepare(OWNER, resultId, "MAJOR_ROW_6")).isEqualTo(prepared);
    }

    @Test
    void rejectsOtherOwnersAndMajorsOutsideTheSealedResult() throws Exception {
        long resultId = completedResult(true);
        String candidate = resultSnapshot(resultId).path("rankedCandidates").get(0).path("majorCode").asText();
        assertThatThrownBy(() -> evidence.prepare(OTHER, resultId, candidate))
                .isInstanceOfSatisfying(ExplorationApiException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> evidence.prepare(OWNER, resultId, "MAJOR_ROW_999999"))
                .isInstanceOfSatisfying(ExplorationApiException.class, e -> assertThat(e.getCode()).isEqualTo("TARGET_MAJOR_NOT_IN_RESULT"));
    }
}
