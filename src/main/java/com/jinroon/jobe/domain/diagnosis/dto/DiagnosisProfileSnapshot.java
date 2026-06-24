package com.jinroon.jobe.domain.diagnosis.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DiagnosisProfileSnapshot(
        String grade,
        String dreamJob,
        List<String> selectedSubjects,
        Double studyHours,
        String learningStyle,
        Integer exploreSpectrum,
        Map<String, Integer> scores,
        String aspiration
) {
    private static final int MIN_SELF_SCORE = 1;
    private static final int MAX_SELF_SCORE = 5;
    private static final int MIN_EXPLORE_SPECTRUM = 0;
    private static final int MAX_EXPLORE_SPECTRUM = 100;

    public DiagnosisProfileSnapshot {
        selectedSubjects = selectedSubjects == null ? List.of() : List.copyOf(selectedSubjects);
        scores = scores == null ? Map.of() : Map.copyOf(scores);
    }

    public static DiagnosisProfileSnapshot empty() {
        return new DiagnosisProfileSnapshot(null, null, List.of(), null, null, null, Map.of(), null);
    }

    public static DiagnosisProfileSnapshot fromJson(String inputSnapshot, ObjectMapper objectMapper) {
        if (inputSnapshot == null || inputSnapshot.isBlank()) {
            return empty();
        }

        try {
            JsonNode root = objectMapper.readTree(inputSnapshot);
            if (root == null || !root.isObject()) {
                return empty();
            }

            return new DiagnosisProfileSnapshot(
                    text(root, "grade"),
                    text(root, "dreamJob"),
                    selectedSubjects(root.get("selectedSubjects")),
                    doubleValue(root.get("studyHours")),
                    text(root, "learningStyle"),
                    exploreSpectrum(root.get("exploreSpectrum")),
                    scores(root.get("scores")),
                    text(root, "aspiration")
            );
        } catch (Exception ignored) {
            return empty();
        }
    }

    public boolean hasProfileSignal() {
        return !selectedSubjects.isEmpty()
                || dreamJob != null
                || aspiration != null
                || studyHours != null
                || learningStyle != null
                || exploreSpectrum != null
                || !scores.isEmpty();
    }

    public int scoreOrNeutral(String key) {
        return scores.getOrDefault(key, 3);
    }

    private static String text(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> selectedSubjects(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                continue;
            }
            String value = item.asText(null);
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private static Double doubleValue(JsonNode node) {
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    private static Integer exploreSpectrum(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        return clamp(node.asInt(), MIN_EXPLORE_SPECTRUM, MAX_EXPLORE_SPECTRUM);
    }

    private static Map<String, Integer> scores(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }

        Map<String, Integer> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && value.isNumber()) {
                values.put(entry.getKey(), clamp(value.asInt(), MIN_SELF_SCORE, MAX_SELF_SCORE));
            }
        });
        return values;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
