package com.jinroon.jobe.domain.exploration.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.catalog.BranchCatalog;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationSessionStatus;
import com.jinroon.jobe.domain.exploration.repository.ExplorationResultRepository;
import com.jinroon.jobe.domain.exploration.repository.ExplorationSessionRepository;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.domain.exploration.service.ExplorationResultStore;
import com.jinroon.jobe.domain.exploration.service.ExplorationRuntimeCatalog;
import com.jinroon.jobe.domain.exploration.service.ExplorationRuntimePolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Selects bounded answer evidence from the sealed result. Major facts require separate evidence approval. */
@Service
@RequiredArgsConstructor
public class MajorExplanationEvidence {
    private static final int MAX_PER_KIND = 4;

    private final ExplorationResultRepository results;
    private final ExplorationSessionRepository sessions;
    private final ExplorationResultStore resultStore;
    private final ExplorationRuntimeCatalog catalogs;
    private final ExplorationRuntimePolicy policy;
    private final ExplorationJson json;

    public enum Kind {
        INTEREST("usedInterestAxes", "interest"),
        ACTIVITY("usedActivityAxes", "activity"),
        LEARNING("usedLearningSets", "learning");

        private final String usedField;
        private final String evidenceField;

        Kind(String usedField, String evidenceField) {
            this.usedField = usedField;
            this.evidenceField = evidenceField;
        }
    }

    public enum ResponseDirection { POSITIVE, NEUTRAL, NEGATIVE }

    public record Signal(Kind kind, String metricCode, String questionId, String questionText, String answerText,
                         ResponseDirection direction, int validResponseCount, boolean inconsistent) {}

    public record Prepared(long resultId, String resultHash, String runMode, boolean testOnly, String majorCode,
                           String majorName, List<Signal> signals) {}

    private record RankedSignal(Signal signal, int value) {}

    @Transactional(readOnly = true)
    public Prepared prepare(long userId, long resultId, String majorCode) {
        if (majorCode == null || !majorCode.matches("MAJOR_ROW_[0-9]{1,9}")) {
            throw new ExplorationApiException(400, "INVALID_REQUEST", "전공 코드 형식을 확인해 주세요.");
        }
        var result = results.findById(resultId).orElseThrow(MajorExplanationEvidence::missing);
        var session = sessions.findById(result.getSessionId()).filter(s -> s.isOwnedBy(userId))
                .orElseThrow(MajorExplanationEvidence::missing);
        policy.authorizeExisting(userId, session.getRunMode());
        if (session.getStatus() != ExplorationSessionStatus.completed) throw ExplorationApiException.integrity();

        var snapshot = resultStore.verifiedSnapshot(result);
        var candidate = find(snapshot.path("rankedCandidates"), "majorCode", majorCode);
        if (candidate == null) {
            throw new ExplorationApiException(422, "TARGET_MAJOR_NOT_IN_RESULT", "진단 결과 후보에서 전공을 선택해 주세요.");
        }
        var frozen = catalogs.load(session.getId());
        if (!frozen.hash().equals(snapshot.path("bundleHash").asText())) throw ExplorationApiException.integrity();
        var major = find(frozen.payload().path("mapping").path("majors"), "major_code", majorCode);
        if (major == null) throw ExplorationApiException.integrity();

        List<RankedSignal> eligible = new ArrayList<>();
        for (JsonNode row : snapshot.path("input").path("questions")) {
            String optionCode = row.path("optionCode").textValue();
            if (!row.path("effective").asBoolean() || optionCode == null) continue;
            RankedSignal signal = resolve(frozen, candidate, row, optionCode);
            if (signal != null) eligible.add(signal);
        }
        List<Signal> signals = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            eligible.stream().filter(row -> row.signal().kind() == kind)
                    .sorted(Comparator.comparing((RankedSignal row) -> row.signal().inconsistent())
                            .thenComparing(Comparator.comparingInt(RankedSignal::value).reversed()))
                    .limit(MAX_PER_KIND).map(RankedSignal::signal).forEach(signals::add);
        }
        return new Prepared(resultId, json.hex(snapshot), session.getRunMode().name(), snapshot.path("testOnly").asBoolean(),
                majorCode, text(major, "major_name"), List.copyOf(signals));
    }

    private RankedSignal resolve(ExplorationRuntimeCatalog.Frozen frozen, JsonNode candidate, JsonNode row,
                                 String optionCode) {
        JsonNode keyNode = row.path("key");
        var key = new BranchCatalog.Key(text(keyNode, "questionId"), text(keyNode, "questionVersion"),
                keyNode.path("comparisonSet").asText(), keyNode.path("variantVersion").asText());
        try {
            var question = frozen.branch().question(key.questionId());
            Kind kind = switch (question.role()) {
                case CANDIDATE_TOPIC -> Kind.INTEREST;
                case CANDIDATE_ACTIVITY -> Kind.ACTIVITY;
                case CANDIDATE_LEARNING -> Kind.LEARNING;
                default -> null;
            };
            if (kind == null || !contains(candidate.path(kind.usedField), question.metric())) return null;
            var option = frozen.branch().option(new BranchCatalog.Answer(key, optionCode, true, true));
            if (option.unknown() || option.value() == null) return null;
            var questionSnapshot = catalogs.questionSnapshot(frozen, key);
            if (!json.hex(questionSnapshot).equals(row.path("snapshotHash").asText())) throw ExplorationApiException.integrity();
            var choice = find(questionSnapshot.path("options"), "option_code", optionCode);
            var axis = candidate.path("evidence").path(kind.evidenceField).path(question.metric());
            if (choice == null || axis.path("validCount").asInt() < 1 || !axis.path("inconsistent").isBoolean()) {
                throw ExplorationApiException.integrity();
            }
            int value = option.value();
            ResponseDirection direction = value == 50 ? ResponseDirection.NEUTRAL
                    : value > 50 ? ResponseDirection.POSITIVE : ResponseDirection.NEGATIVE;
            return new RankedSignal(new Signal(kind, question.metric(), question.id(),
                    text(questionSnapshot.path("question"), "question_text"), text(choice, "option_text"),
                    direction, axis.path("validCount").asInt(), axis.path("inconsistent").asBoolean()), value);
        } catch (IllegalArgumentException e) {
            throw ExplorationApiException.integrity();
        }
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode row : array) if (value.equals(row.textValue())) return true;
        return false;
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode row : array) if (value.equals(row.path(field).textValue())) return row;
        return null;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).textValue();
        if (value == null || value.isBlank()) throw ExplorationApiException.integrity();
        return value;
    }

    private static ExplorationApiException missing() {
        return new ExplorationApiException(404, "RESOURCE_NOT_FOUND", "진단 결과를 찾을 수 없습니다.");
    }
}
