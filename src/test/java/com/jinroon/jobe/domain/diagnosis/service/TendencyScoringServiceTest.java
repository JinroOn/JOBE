package com.jinroon.jobe.domain.diagnosis.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.diagnosis.service.TendencyScoringService.Axis;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 성향 점수 서버 계산 테스트.
 * 문항 축 구성은 프론트(JOFE Diagnosis/Tendency의 QUESTIONS)와 같아야 하므로 여기서 고정한다.
 * 프론트 문항을 바꾸면 이 테스트가 깨지도록 해서 두 곳을 함께 고치게 한다.
 */
class TendencyScoringServiceTest {

    private final TendencyScoringService service = new TendencyScoringService(new ObjectMapper());

    @Test
    void questionAxesMatchFrontendDefinition() {
        // 프론트 QUESTIONS의 {a.axis, b.axis}를 순서대로 옮긴 것
        List<String> expected = List.of(
                "LOGICAL_INQUIRY/ART_CREATIVE",
                "PRACTICAL_TECH/SOCIAL_COOPERATION",
                "DATA_ANALYTICS/LIFE_HEALTH",
                "THEORY_ACADEMIC/PRACTICAL_TECH",
                "SYSTEM_OPERATION/EDUCATION_GUIDE",
                "LOGICAL_INQUIRY/SOCIAL_COOPERATION",
                "ART_CREATIVE/DATA_ANALYTICS",
                "THEORY_ACADEMIC/SYSTEM_OPERATION",
                "LIFE_HEALTH/PRACTICAL_TECH",
                "LOGICAL_INQUIRY/EDUCATION_GUIDE",
                "SOCIAL_COOPERATION/DATA_ANALYTICS",
                "SYSTEM_OPERATION/ART_CREATIVE"
        );
        assertThat(TendencyScoringService.QUESTION_AXES)
                .extracting(pair -> pair[0] + "/" + pair[1])
                .containsExactlyElementsOf(expected);
    }

    @Test
    void everyAxisAppearsAtLeastTwice() {
        Map<Axis, Integer> appearances = new EnumMap<>(Axis.class);
        TendencyScoringService.QUESTION_AXES.forEach(pair -> {
            appearances.merge(pair[0], 1, Integer::sum);
            appearances.merge(pair[1], 1, Integer::sum);
        });
        assertThat(appearances).hasSize(Axis.values().length);
        assertThat(appearances.values()).allMatch(count -> count >= 2);
    }

    @Test
    void scoresAllAAnswersLikeFrontendFormula() {
        Map<Axis, Float> scores = service.score(all("A"));

        assertThat(scores.get(Axis.LOGICAL_INQUIRY)).isEqualTo(100.0f);   // 3/3
        assertThat(scores.get(Axis.THEORY_ACADEMIC)).isEqualTo(100.0f);   // 2/2
        assertThat(scores.get(Axis.SYSTEM_OPERATION)).isEqualTo(67.0f);   // 2/3 = 66.7 -> 67
        assertThat(scores.get(Axis.LIFE_HEALTH)).isEqualTo(50.0f);        // 1/2
        assertThat(scores.get(Axis.PRACTICAL_TECH)).isEqualTo(33.0f);     // 1/3
        assertThat(scores.get(Axis.EDUCATION_GUIDE)).isEqualTo(0.0f);     // 0/2
    }

    @Test
    void partialAnswersOnlyCountAnsweredQuestions() {
        Map<Integer, String> answers = new LinkedHashMap<>();
        answers.put(0, "B"); // ART_CREATIVE 선택, LOGICAL_INQUIRY 노출

        Map<Axis, Float> scores = service.score(answers);

        assertThat(scores.get(Axis.ART_CREATIVE)).isEqualTo(100.0f);
        assertThat(scores.get(Axis.LOGICAL_INQUIRY)).isEqualTo(0.0f);
        assertThat(scores.get(Axis.DATA_ANALYTICS)).isEqualTo(0.0f); // 노출 안 됨
    }

    @Test
    void readsAnswersFromSnapshotAndDropsInvalidEntries() {
        String snapshot = "{\"dreamJob\":\"개발자\",\"tendencyAnswers\":"
                + "{\"0\":\"A\",\"1\":\"B\",\"12\":\"A\",\"x\":\"A\",\"2\":\"C\",\"-1\":\"A\"}}";

        Map<Integer, String> answers = service.readAnswers(snapshot);

        assertThat(answers).containsOnly(Map.entry(0, "A"), Map.entry(1, "B"));
        assertThat(service.isComplete(answers)).isFalse();
    }

    @Test
    void missingOrBrokenSnapshotGivesNoAnswers() {
        assertThat(service.readAnswers(null)).isEmpty();
        assertThat(service.readAnswers("")).isEmpty();
        assertThat(service.readAnswers("{not json")).isEmpty();
        assertThat(service.readAnswers("{\"tendencyAnswers\":[\"A\"]}")).isEmpty();
    }

    @Test
    void completeAnswersAreDetected() {
        assertThat(service.isComplete(all("B"))).isTrue();
    }

    private Map<Integer, String> all(String choice) {
        Map<Integer, String> answers = new LinkedHashMap<>();
        for (int i = 0; i < TendencyScoringService.QUESTION_COUNT; i++) {
            answers.put(i, choice);
        }
        return answers;
    }
}
