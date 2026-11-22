package com.jinroon.jobe.domain.exploration.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static com.jinroon.jobe.domain.exploration.scoring.ExplorationScoreCalculator.*;

/** In-memory adapter for already validated, hash-pinned catalog snapshots; performs no IO or approval. */
public record ScoringCatalog(String rulesetVersion, String mappingVersion, String semanticVersion,
                             ExplorationScoreCalculator calculator, List<MajorProfile> majors) {
    public static final String LEGACY_RULESET_VERSION = "RULESET_V1_5_IMPLEMENTATION_CANDIDATE";
    public static final String CURRENT_RULESET_VERSION = "RULESET_V1_6_IMPLEMENTATION_CANDIDATE";
    private static final int AFFIRMATIVE_INTEREST_MINIMUM = 75;

    public ScoringCatalog { majors = List.copyOf(majors); }

    public static ScoringCatalog fromSnapshots(JsonNode rules, JsonNode mapping, JsonNode semantic) {
        String version = text(rules, "ruleset_version");
        require(version.equals(LEGACY_RULESET_VERSION) || version.equals(CURRENT_RULESET_VERSION), "Unsupported ruleset version");
        if (version.equals(CURRENT_RULESET_VERSION)) {
            JsonNode minimum = rules.path("thresholds").path("affirmative_interest_response_gte");
            require(minimum.isIntegralNumber() && minimum.intValue() == AFFIRMATIVE_INTEREST_MINIMUM,
                    "Unsupported affirmative interest threshold");
        }
        String mappingVersion = text(mapping, "major_mapping_version"), semanticVersion = text(semantic, "version");
        require(mappingVersion.equals("MAJOR_MAPPING_V1_2_IMPLEMENTATION_CANDIDATE")
                && text(rules.path("catalog_versions"), "major_mapping").equals(mappingVersion), "Mapping version mismatch");
        require(text(mapping, "schema_version").equals("1.2"), "Unsupported mapping schema");
        require(semanticVersion.equals("SEMANTIC_CONTRACT_V1_0")
                && text(rules, "semantic_contract_version").equals(semanticVersion), "Semantic version mismatch");
        JsonNode policy = rules.path("activity_scoring");
        require(text(policy, "axis_aggregation").equals("MEAN_OF_VALID_RESPONSES_PER_METRIC")
                && text(policy, "axis_order").equals("MAJOR_ACTIVITY_AXES_ORDER")
                && text(policy, "missing_policy").equals("DROP_MISSING_KEEP_ORIGINAL_POSITION_WEIGHTS_RENORMALIZE")
                && policy.path("required_distinct_axes").asInt() == 3
                && policy.path("min_valid_distinct_axes").asInt() == 2
                && policy.path("sort_by_response_score").isBoolean() && !policy.path("sort_by_response_score").asBoolean()
                && policy.path("round_intermediate").isBoolean() && !policy.path("round_intermediate").asBoolean()
                && policy.path("zero_is_valid").asBoolean(), "Unsupported activity policy");
        require(text(rules, "tie_breaker").equals("MAJOR_CODE_ASC"), "Unsupported tie breaker");
        JsonNode weights = rules.path("weights");
        require(weights.path("renormalize_missing_components").asBoolean(), "Missing-component normalization required");
        Rules config = new Rules(weights(weights, "primary_interest", 2), weights(weights, "hybrid_interest", 2),
                weights(weights, "activity", 3), componentWeights(weights.path("without_learning"), false),
                componentWeights(weights.path("with_learning"), true), Set.of(0,25,50,75,100),
                number(rules.path("thresholds"), "axis_inconsistent_gap_gte"),
                number(rules.path("thresholds"), "positive_topic_gte"));
        Set<Integer> scale = new HashSet<>();
        require(rules.path("score_scale").isArray(), "Missing score scale");
        rules.path("score_scale").forEach(n -> { require(n.isIntegralNumber(), "Invalid score scale"); scale.add(n.intValue()); });
        require(scale.equals(config.scale()) && rules.path("score_scale").size() == 5, "Unsupported score scale");
        require(new HashSet<>(strings(rules, "score_roles")).equals(Set.of("CANDIDATE_TOPIC","CANDIDATE_ACTIVITY","CANDIDATE_LEARNING")), "Unsupported scoring roles");
        ExplorationScoreCalculator calculator = new ExplorationScoreCalculator(config,
                Set.copyOf(strings(semantic, "interest_axes")), Set.copyOf(strings(semantic, "activity_axes")),
                Set.copyOf(strings(semantic, "learning_sets")), Set.copyOf(strings(semantic, "groups")));
        require(mapping.path("majors").isArray(), "Missing major profiles");
        List<MajorProfile> majors = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (JsonNode row : mapping.path("majors")) {
            List<String> primary = strings(row, "primary_interest_axes"), secondary = strings(row, "secondary_interest_axes");
            require(primary.size() == 1 && secondary.size() == 1, "Exactly one primary and secondary axis required");
            MajorProfile major = new MajorProfile(text(row,"major_code"), text(row,"exploration_group"),
                    InterestStructure.valueOf(text(row,"interest_structure")), primary.get(0), secondary.get(0),
                    strings(row,"activity_axes"), strings(row,"learning_content_sets"));
            require(codes.add(major.majorCode()), "Duplicate major code");
            calculator.validate(major); majors.add(major);
        }
        require(!majors.isEmpty(), "Empty major profiles");
        return new ScoringCatalog(version, mappingVersion, semanticVersion, calculator, majors);
    }
    public boolean missingAffirmativeInterest(List<Response> responses) {
        return rulesetVersion.equals(CURRENT_RULESET_VERSION) && responses.stream().noneMatch(response ->
                response.current() && response.effective() && response.role() == Role.CANDIDATE_TOPIC
                        && response.numericValue() != null && response.numericValue() >= AFFIRMATIVE_INTEREST_MINIMUM);
    }

    private static List<Double> componentWeights(JsonNode node, boolean learning) {
        List<Double> result = new ArrayList<>(List.of(number(node,"interest"), number(node,"activity")));
        if (learning) result.add(number(node,"learning"));
        return normalized(result);
    }
    private static List<Double> weights(JsonNode node, String key, int count) {
        require(node.path(key).isArray() && node.path(key).size() == count, "Invalid weights: " + key);
        List<Double> result = new ArrayList<>();
        node.path(key).forEach(n -> { require(n.isNumber(), "Non-numeric weight"); result.add(n.doubleValue()); });
        return normalized(result);
    }
    private static List<Double> normalized(List<Double> weights) {
        require(weights.stream().allMatch(v -> Double.isFinite(v) && v > 0)
                && Math.abs(weights.stream().mapToDouble(Double::doubleValue).sum() - 1) < 1e-12, "Invalid normalized weights");
        return List.copyOf(weights);
    }
    private static double number(JsonNode node, String key) {
        require(node.path(key).isNumber(), "Missing number: " + key);
        double value = node.path(key).doubleValue();
        require(Double.isFinite(value) && value >= 0 && value <= 100, "Invalid number: " + key);
        return value;
    }
    private static String text(JsonNode node, String key) {
        require(node.path(key).isTextual() && !node.path(key).asText().isBlank(), "Missing string: " + key);
        return node.path(key).asText();
    }
    private static List<String> strings(JsonNode node, String key) {
        require(node.path(key).isArray(), "Missing array: " + key);
        List<String> result = new ArrayList<>();
        node.path(key).forEach(n -> { require(n.isTextual() && !n.asText().isBlank(), "Invalid string array: " + key); result.add(n.asText()); });
        return List.copyOf(result);
    }
}
