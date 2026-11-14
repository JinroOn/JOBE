package com.jinroon.jobe.domain.diagnosis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 성향 검사(A/B 12문항) 점수를 서버에서 계산한다.
 *
 * 문항 정의는 프론트 JOFE/src/pages/Diagnosis/Tendency/index.tsx 의 QUESTIONS와 같은 순서·축이어야 한다.
 * 문항을 바꾸면 두 곳을 함께 고친다(TendencyScoringServiceTest가 축 구성을 고정한다).
 * 계산식도 프론트 computeTendencyVector와 같다: 축별 (선택 수 / 그 축이 나온 응답 문항 수) × 100, 반올림.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TendencyScoringService {

    public enum Axis {
        LOGICAL_INQUIRY,
        PRACTICAL_TECH,
        ART_CREATIVE,
        SOCIAL_COOPERATION,
        LIFE_HEALTH,
        EDUCATION_GUIDE,
        THEORY_ACADEMIC,
        DATA_ANALYTICS,
        SYSTEM_OPERATION
    }

    /** 문항 순서대로 {A 선택 축, B 선택 축}. 인덱스는 inputSnapshot.tendencyAnswers의 키(0부터)와 같다. */
    static final List<Axis[]> QUESTION_AXES = List.of(
            new Axis[]{Axis.LOGICAL_INQUIRY, Axis.ART_CREATIVE},
            new Axis[]{Axis.PRACTICAL_TECH, Axis.SOCIAL_COOPERATION},
            new Axis[]{Axis.DATA_ANALYTICS, Axis.LIFE_HEALTH},
            new Axis[]{Axis.THEORY_ACADEMIC, Axis.PRACTICAL_TECH},
            new Axis[]{Axis.SYSTEM_OPERATION, Axis.EDUCATION_GUIDE},
            new Axis[]{Axis.LOGICAL_INQUIRY, Axis.SOCIAL_COOPERATION},
            new Axis[]{Axis.ART_CREATIVE, Axis.DATA_ANALYTICS},
            new Axis[]{Axis.THEORY_ACADEMIC, Axis.SYSTEM_OPERATION},
            new Axis[]{Axis.LIFE_HEALTH, Axis.PRACTICAL_TECH},
            new Axis[]{Axis.LOGICAL_INQUIRY, Axis.EDUCATION_GUIDE},
            new Axis[]{Axis.SOCIAL_COOPERATION, Axis.DATA_ANALYTICS},
            new Axis[]{Axis.SYSTEM_OPERATION, Axis.ART_CREATIVE}
    );

    public static final int QUESTION_COUNT = QUESTION_AXES.size();

    private final ObjectMapper objectMapper;

    /**
     * inputSnapshot의 tendencyAnswers({"0":"A","1":"B",...})를 읽는다.
     * 범위를 벗어난 문항 번호나 A/B가 아닌 값은 버린다. 스냅샷이 없거나 깨졌으면 빈 맵.
     */
    public Map<Integer, String> readAnswers(String inputSnapshot) {
        Map<Integer, String> answers = new LinkedHashMap<>();
        if (inputSnapshot == null || inputSnapshot.isBlank()) {
            return answers;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(inputSnapshot).path("tendencyAnswers");
        } catch (Exception exception) {
            log.debug("tendency answers parse failed errorType={}", exception.getClass().getSimpleName());
            return answers;
        }
        if (!node.isObject()) {
            return answers;
        }
        node.fields().forEachRemaining(entry -> {
            Integer index = parseIndex(entry.getKey());
            String choice = entry.getValue().asText("");
            if (index != null && index >= 0 && index < QUESTION_COUNT && ("A".equals(choice) || "B".equals(choice))) {
                answers.put(index, choice);
            }
        });
        return answers;
    }

    public boolean isComplete(Map<Integer, String> answers) {
        return answers.size() == QUESTION_COUNT;
    }

    /** 축별 0~100 점수. 응답에 한 번도 나오지 않은 축은 0. */
    public Map<Axis, Float> score(Map<Integer, String> answers) {
        Map<Axis, Integer> chosen = new EnumMap<>(Axis.class);
        Map<Axis, Integer> shown = new EnumMap<>(Axis.class);
        answers.forEach((index, choice) -> {
            Axis[] pair = QUESTION_AXES.get(index);
            shown.merge(pair[0], 1, Integer::sum);
            shown.merge(pair[1], 1, Integer::sum);
            chosen.merge("A".equals(choice) ? pair[0] : pair[1], 1, Integer::sum);
        });
        Map<Axis, Float> scores = new EnumMap<>(Axis.class);
        for (Axis axis : Axis.values()) {
            int total = shown.getOrDefault(axis, 0);
            int count = chosen.getOrDefault(axis, 0);
            scores.put(axis, total == 0 ? 0.0f : (float) Math.round(count * 100.0 / total));
        }
        return scores;
    }

    private static Integer parseIndex(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
