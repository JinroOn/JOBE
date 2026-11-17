package com.jinroon.jobe.domain.exploration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.branching.CandidateEngine.FixedCandidates;
import com.jinroon.jobe.domain.exploration.branching.QuestionBranchEngine;
import com.jinroon.jobe.domain.exploration.catalog.BranchCatalog;
import com.jinroon.jobe.domain.exploration.scoring.ResultEvidencePolicy;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import static com.jinroon.jobe.domain.exploration.scoring.ExplorationScoreCalculator.*;

@Component
@RequiredArgsConstructor
public class ExplorationResultAssembler {
    public static final String CONTRACT = "RESULT_CONTRACT_V1_0";
    private final ExplorationJson json;
    private final com.jinroon.jobe.domain.exploration.display.ExplorationResultDisplay resultDisplay;

    public ObjectNode assemble(long sessionId, String completedAt, String reason, ExplorationRuntimeCatalog.Frozen frozen,
                               FixedCandidates fixed, List<BranchCatalog.Answer> answers, JsonNode input) {
        var engine = new QuestionBranchEngine(frozen.branch());
        var responses = frozen.branch().scoringResponses(answers);
        var axes = frozen.branch().scoring().calculator().aggregate(responses);
        var display = engine.candidates().rerank(fixed, responses);
        int validScreen = (int)frozen.branch().active(answers).stream().filter(a ->
                frozen.branch().question(a.key().questionId()).module().equals("INTEREST_SCREEN") && frozen.branch().option(a).value() != null).count();
        Set<String> used = new TreeSet<>();
        display.firstScreen().forEach(m -> used.addAll(m.usedInterestAxes()));
        var evidence = new ResultEvidencePolicy().classify(display.candidates().size(), validScreen,
                frozen.payload().path("rules").path("thresholds").path("screen_valid_min").asInt(), used.stream().map(axes.interest()::get).toList());
        TreeSet<String> reasonCodes = new TreeSet<>(evidence.reasonCodes());
        if (display.candidates().isEmpty()) reasonCodes.add(axes.interest().values().stream().allMatch(a -> a.validCount() == 0)
                ? "INTEREST_NO_VALID_RESPONSES" : "NO_POSITIVE_TOPIC_EVIDENCE");
        ObjectNode snapshot = json.object();
        snapshot.put("resultContractVersion", CONTRACT);
        snapshot.put("sessionId", sessionId);
        snapshot.put("sessionStatus", "completed");
        snapshot.put("completedAt", completedAt);
        snapshot.put("status", evidence.status().name());
        snapshot.put("completionReason", reason);
        snapshot.set("reasonCodes", json.tree(reasonCodes));
        snapshot.set("versions", frozen.payload().path("versions").deepCopy());
        snapshot.set("sourceHashes", frozen.payload().path("sourceHashes").deepCopy());
        snapshot.put("bundleHash", frozen.hash());
        snapshot.set("input", input);
        snapshot.put("inputHash", json.hex(input));
        snapshot.set("fixedCandidates", json.tree(fixed));
        snapshot.set("rankedCandidates", json.tree(display.candidates()));
        snapshot.set("axes", json.tree(axes));
        snapshot.set("firstScreen", json.tree(display.firstScreen()));

        ObjectNode presentation = snapshot.putObject("presentation");
        presentation.put("source", "NOT_COLLECTED");
        presentation.put("adjustedByUserReaction", false);
        if (input.path("presentation").isObject()) presentation.setAll((ObjectNode) input.path("presentation").deepCopy());
        // OPEN-04 controls unfolding only. Neither reaction nor openness changes rank or candidate membership.
        for (var answer : frozen.branch().active(answers)) if (answer.key().questionId().equals("OPEN-04"))
            presentation.put("unfamiliarRange", frozen.branch().option(answer).meaning());
        for (var answer : frozen.branch().active(answers)) if (answer.key().questionId().equals("OPEN-03"))
            presentation.put("unexpectedAction", frozen.branch().option(answer).meaning());
        var summaries = snapshot.putArray("majorSummaries");
        Map<String, JsonNode> names = new HashMap<>();
        frozen.payload().path("mapping").path("majors").forEach(m -> names.put(m.path("major_code").asText(), m));
        for (var major : display.firstScreen()) {
            ObjectNode summary = summaries.addObject();
            summary.put("majorCode", major.majorCode());
            summary.put("majorName", names.get(major.majorCode()).path("major_name").asText());
            summary.put("groupCode", major.group());
            summary.set("interestEvidence", interestEvidence(major.evidence().interest()));
            summary.set("usedInterestAxes", json.tree(major.usedInterestAxes()));
            summary.set("usedActivityAxes", json.tree(major.usedActivityAxes()));
            summary.set("usedLearningSets", json.tree(major.usedLearningSets()));
            summary.put("explanation", "입력한 흥미와 연결된 활동·학습 응답을 바탕으로 탐색할 후보입니다. 능력이나 합격 가능성을 판정한 결과가 아닙니다.");
        }
        var groups = snapshot.putArray("groupSummaries");
        for (String group : fixed.groupCodes()) {
            List<String> codes = display.firstScreen().stream().filter(m -> m.group().equals(group)).map(MajorScore::majorCode).toList();
            if (!codes.isEmpty()) { var row = groups.addObject(); row.put("groupCode", group); row.set("majorCodes", json.tree(codes)); }
        }
        ObjectNode overall = snapshot.putObject("evidenceLevel");
        overall.put("screenValidCount", validScreen);
        overall.put("screenRequiredMinimum", frozen.payload().path("rules").path("thresholds").path("screen_valid_min").asInt());
        Map<String, AxisScore> usedEvidence = new TreeMap<>(); used.forEach(metric -> usedEvidence.put(metric, axes.interest().get(metric)));
        overall.set("usedInterestAxes", interestEvidence(usedEvidence));
        // Preserve selected preparation responses, not unsupported factual claims or score penalties.
        var cautions = snapshot.putArray("cautions");
        for (var answer : frozen.branch().active(answers)) {
            var q = frozen.branch().question(answer.key().questionId());
            if (!Set.of("CAUTION_CHECK", "SUBJECT_BURDEN").contains(q.module()) || frozen.branch().option(answer).unknown()) continue;
            JsonNode source = null, option = null;
            for (JsonNode row : frozen.payload().path("questions")) if (row.path("question_id").asText().equals(q.id())) source = row;
            for (JsonNode row : frozen.payload().path("options")) if (row.path("question_id").asText().equals(q.id()) && row.path("option_code").asText().equals(answer.optionCode())) option = row;
            if (source == null || option == null) throw new IllegalArgumentException("Missing preparation snapshot");
            var caution = cautions.addObject();
            caution.put("questionId", q.id()); caution.put("questionText", source.path("question_text").asText());
            caution.put("answerText", option.path("option_text").asText()); caution.put("usage", "PREPARATION_RESPONSE_ONLY");
        }
        snapshot.set("display", resultDisplay.freeze(snapshot, frozen.payload()));
        boolean adjusted = false;
        for (JsonNode major : snapshot.path("display").path("majors")) adjusted |= major.path("collapsed").asBoolean();
        presentation.put("adjustedByUserReaction", adjusted);
        return snapshot;
    }

    private JsonNode interestEvidence(Map<String, AxisScore> axes) {
        var result = json.array();
        new TreeMap<>(axes).forEach((metric, axis) -> {
            var row = result.addObject(); row.put("metricCode", metric); row.put("validCount", axis.validCount());
            row.put("evidenceLevel", axis.interestEvidence().name()); row.put("inconsistent", axis.inconsistent());
        });
        return result;
    }
}
