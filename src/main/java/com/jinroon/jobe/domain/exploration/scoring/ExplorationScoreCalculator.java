package com.jinroon.jobe.domain.exploration.scoring;

import java.util.*;

/** Pure arithmetic over server-resolved, pinned inputs. Does not select candidates or issue questions. */
public final class ExplorationScoreCalculator {
    public enum Role {
        CANDIDATE_TOPIC, CANDIDATE_ACTIVITY, CANDIDATE_LEARNING,
        ROUTING, PRESENTATION_ONLY, COMPARISON_ONLY, CAUTION_INPUT, CAUTION_ONLY,
        ACTION_SUBSTITUTION, EXPLORATION_SUPPORT, POST_RESULT_ONLY
    }
    public enum InterestStructure { SINGLE, PRIMARY, HYBRID }
    public enum InterestEvidence { NO_EVIDENCE, LOW_EVIDENCE, CONFIRMED }

    /** numericValue is resolved from the server option snapshot, never accepted directly from a client. */
    public record Response(String questionId, Role role, String metric, Integer numericValue,
                           boolean current, boolean effective) {
        public Response {
            require(questionId != null && !questionId.isBlank(), "questionId is required");
            Objects.requireNonNull(role, "role");
            require(metric != null && !metric.isBlank(), "metric is required");
        }
    }
    public record AxisScore(Double score, int validCount, boolean inconsistent) {
        public InterestEvidence interestEvidence() {
            return validCount == 0 ? InterestEvidence.NO_EVIDENCE
                    : validCount == 1 ? InterestEvidence.LOW_EVIDENCE : InterestEvidence.CONFIRMED;
        }
    }
    public record Axes(Map<String, AxisScore> interest, Map<String, AxisScore> activity,
                       Map<String, AxisScore> learning) {
        public Axes {
            interest = immutable(interest); activity = immutable(activity); learning = immutable(learning);
        }
    }
    public record MajorProfile(String majorCode, String group, InterestStructure structure,
                               String primary, String secondary, List<String> activityAxes,
                               List<String> learningSets) {
        public MajorProfile {
            require(majorCode != null && !majorCode.isBlank(), "majorCode is required");
            Objects.requireNonNull(structure, "structure");
            activityAxes = List.copyOf(activityAxes); learningSets = List.copyOf(learningSets);
        }
    }
    /** Includes linked axis evidence even when insufficient to contribute; used components are explicit. */
    public record MajorScore(String majorCode, String group, Double topic, Double activity, Double learning,
                             Double total, boolean positiveTopicEvidence, Axes evidence,
                             List<String> usedInterestAxes, List<String> usedActivityAxes,
                             List<String> usedLearningSets) {
        public MajorScore {
            usedInterestAxes = List.copyOf(usedInterestAxes);
            usedActivityAxes = List.copyOf(usedActivityAxes);
            usedLearningSets = List.copyOf(usedLearningSets);
        }
    }
    record Rules(List<Double> primary, List<Double> hybrid, List<Double> activity,
                 List<Double> withoutLearning, List<Double> withLearning,
                 Set<Integer> scale, double inconsistentGap, double positiveThreshold) {}

    private final Rules rules;
    private final Set<String> interestMetrics, activityMetrics, learningMetrics, groups;

    ExplorationScoreCalculator(Rules rules, Set<String> interest, Set<String> activity,
                               Set<String> learning, Set<String> groups) {
        this.rules = rules;
        interestMetrics = Set.copyOf(interest); activityMetrics = Set.copyOf(activity);
        learningMetrics = Set.copyOf(learning); this.groups = Set.copyOf(groups);
    }

    public Axes aggregate(List<Response> responses) {
        Map<String, List<Integer>> interest = buckets(interestMetrics), activity = buckets(activityMetrics),
                learning = buckets(learningMetrics);
        Set<String> seen = new HashSet<>();
        for (Response response : responses) {
            if (!response.current() || !response.effective()) continue;
            require(seen.add(response.questionId()), "Duplicate current effective question: " + response.questionId());
            Map<String, List<Integer>> target = switch (response.role()) {
                case CANDIDATE_TOPIC -> interest;
                case CANDIDATE_ACTIVITY -> activity;
                case CANDIDATE_LEARNING -> learning;
                default -> null;
            };
            if (target == null) continue;
            require(target.containsKey(response.metric()), "Unknown metric or role mismatch: " + response.metric());
            if (response.numericValue() != null) {
                require(rules.scale().contains(response.numericValue()), "Value outside pinned score scale");
                target.get(response.metric()).add(response.numericValue());
            }
        }
        return new Axes(summarize(interest, true), summarize(activity, false), summarize(learning, false));
    }

    public MajorScore score(MajorProfile major, Axes axes) {
        validate(major);
        List<String> interest = major.structure() == InterestStructure.SINGLE
                ? List.of(major.primary()) : List.of(major.primary(), major.secondary());
        List<Double> topicWeights = switch (major.structure()) {
            case SINGLE -> List.of(1.0);
            case PRIMARY -> rules.primary();
            case HYBRID -> rules.hybrid();
        };
        Double topic = weighted(values(axes.interest(), interest), topicWeights);
        List<Double> activityValues = values(axes.activity(), major.activityAxes());
        Double activity = activityValues.stream().filter(Objects::nonNull).count() < 2
                ? null : weighted(activityValues, rules.activity());
        Double learning = major.learningSets().isEmpty() ? null
                : value(axes.learning(), major.learningSets().get(0));
        // Topic is mandatory, including when activity/learning happen to be high.
        Double total = topic == null ? null : learning == null
                ? weighted(Arrays.asList(topic, activity), rules.withoutLearning())
                : weighted(Arrays.asList(topic, activity, learning), rules.withLearning());
        boolean positive = interest.stream().map(metric -> value(axes.interest(), metric))
                .anyMatch(score -> score != null && score >= rules.positiveThreshold());
        Axes evidence = new Axes(subset(axes.interest(), interest), subset(axes.activity(), major.activityAxes()),
                subset(axes.learning(), major.learningSets()));
        return new MajorScore(major.majorCode(), major.group(), topic, activity, learning, total, positive, evidence,
                present(axes.interest(), interest), activity == null ? List.of() : present(axes.activity(), major.activityAxes()),
                learning == null ? List.of() : major.learningSets());
    }

    /** Scores the supplied set only; callers must select/fix candidates in a separate engine. Nulls sort last. */
    public List<MajorScore> scoreAndSort(List<MajorProfile> majors, List<Response> responses) {
        Axes axes = aggregate(responses);
        Set<String> seen = new HashSet<>();
        for (MajorProfile major : majors) require(seen.add(major.majorCode()), "Duplicate major code");
        return majors.stream().map(major -> score(major, axes))
                .sorted(Comparator.comparing(MajorScore::total, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(MajorScore::majorCode)).toList();
    }

    void validate(MajorProfile major) {
        require(groups.contains(major.group()), "Unknown exploration group");
        require(interestMetrics.contains(major.primary()) && interestMetrics.contains(major.secondary()), "Unknown interest axis");
        require((major.structure() == InterestStructure.SINGLE) == major.primary().equals(major.secondary()),
                "Interest structure does not match axis identity");
        require(major.activityAxes().size() == 3 && new HashSet<>(major.activityAxes()).size() == 3
                && activityMetrics.containsAll(major.activityAxes()), "Exactly three distinct known activity axes required");
        // Current mapping has one learning set per major. Do not invent a multi-set weighting policy.
        require(major.learningSets().size() <= 1 && learningMetrics.containsAll(major.learningSets()), "Unsupported learning set mapping");
    }

    private Map<String, AxisScore> summarize(Map<String, List<Integer>> buckets, boolean interest) {
        Map<String, AxisScore> result = new TreeMap<>();
        buckets.forEach((metric, values) -> {
            Double score = values.isEmpty() ? null : values.stream().mapToDouble(Integer::doubleValue).average().orElseThrow();
            boolean inconsistent = interest && values.size() > 1
                    && Collections.max(values) - Collections.min(values) >= rules.inconsistentGap();
            result.put(metric, new AxisScore(score, values.size(), inconsistent));
        });
        return result;
    }
    private static Double weighted(List<Double> values, List<Double> weights) {
        double sum = 0, denominator = 0;
        for (int i = 0; i < values.size(); i++) if (values.get(i) != null) {
            sum += values.get(i) * weights.get(i); denominator += weights.get(i);
        }
        return denominator == 0 ? null : sum / denominator;
    }
    private static List<Double> values(Map<String, AxisScore> axes, List<String> metrics) {
        return metrics.stream().map(metric -> value(axes, metric)).toList();
    }
    private static Double value(Map<String, AxisScore> axes, String metric) {
        AxisScore axis = axes.get(metric);
        require(axis != null, "Missing axis summary: " + metric);
        return axis.score();
    }
    private static Map<String, AxisScore> subset(Map<String, AxisScore> axes, List<String> metrics) {
        Map<String, AxisScore> result = new TreeMap<>();
        for (String metric : metrics) { value(axes, metric); result.put(metric, axes.get(metric)); }
        return result;
    }
    private static List<String> present(Map<String, AxisScore> axes, List<String> metrics) {
        return metrics.stream().filter(metric -> value(axes, metric) != null).toList();
    }
    private static Map<String, List<Integer>> buckets(Set<String> metrics) {
        Map<String, List<Integer>> result = new TreeMap<>();
        metrics.forEach(metric -> result.put(metric, new ArrayList<>()));
        return result;
    }
    private static <T> Map<String, T> immutable(Map<String, T> values) {
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
