package com.jinroon.jobe.domain.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Opt-in finite audit: every answer goes through authenticated HTTP against an actually issued snapshot. */
@Tag("exploration-reachability")
class ExplorationReachabilityAuditTest extends ExplorationApiTestSupport {
    private static final Path RECIPES = Path.of("datasets/exploration-validation/reachability-profiles.json");
    private static final String HARNESS = "D08_HTTP_HARNESS_V1_1";
    private final Set<String> questionCoverage = new TreeSet<>();
    private final List<JsonNode> runs = new ArrayList<>();
    private ExplorationJson json;
    private Path directory;
    private int requests;
    private ObjectNode activeAudit;
    @org.springframework.beans.factory.annotation.Autowired com.jinroon.jobe.domain.exploration.service.ExplorationRuntimeCatalog runtime;
    @org.springframework.beans.factory.annotation.Autowired com.jinroon.jobe.domain.exploration.service.ExplorationStageStore stages;
    @org.springframework.beans.factory.annotation.Autowired com.jinroon.jobe.domain.exploration.service.ExplorationResultAssembler assembler;

    @Test void executeAll376RecipesAndBoundedReactionAndReproducibilityProbes() throws Exception {
        json = new ExplorationJson(mapper);
        directory = Path.of(Objects.requireNonNull(System.getProperty("exploration.reachability.report-dir")));
        Files.createDirectories(directory);
        JsonNode fixture = mapper.readTree(Files.readString(RECIPES));
        assertThat(fixture.path("runtime_status").asText()).isEqualTo("NOT_EXECUTED");
        assertThat(fixture.path("profiles").size()).isEqualTo(376);
        int limit = Integer.parseInt(System.getProperty("exploration.reachability.max-recipes", "376"));
        int offset = Integer.parseInt(System.getProperty("exploration.reachability.offset", "0"));
        assertThat(offset).isBetween(0,374); assertThat(limit).isBetween(2,376-offset);
        ObjectNode manifest = json.object();
        manifest.put("harnessVersion", HARNESS); manifest.put("engineCommit", gitHead());
        manifest.put("startedAt", java.time.Instant.now().toString());
        manifest.put("harnessFileHash", hex(Files.readAllBytes(Path.of("src/test/java/com/jinroon/jobe/domain/exploration/ExplorationReachabilityAuditTest.java"))));
        manifest.put("workingTreeIncludesUncommittedImplementation", true);
        manifest.put("worktreeSourceHash", sourceHash()); manifest.put("fixtureFileHash", hex(Files.readAllBytes(RECIPES)));
        manifest.put("fixtureVersion", fixture.path("fixture_version").asText());
        manifest.put("recipeOffset",offset); manifest.put("recipeCount",limit);
        manifest.put("mode", "ISOLATED_DEV_SYNTHETIC_HTTP");
        manifest.put("recipePolicy", "BROAD; comparison UNSURE; only issued options; no forced confirmation/evidence");
        manifest.put("browserOrStudentValidation", false); manifest.put("contentApprovedByThisRun", false);
        write("manifest.json", manifest);
        Files.writeString(directory.resolve("runs.jsonl"), "", StandardCharsets.UTF_8);
        List<String> errors = new ArrayList<>();
        Map<String, JsonNode> originals = new HashMap<>();
        int n = 0;
        for (int recipeIndex = offset; recipeIndex < offset + limit; recipeIndex++) {
            JsonNode recipe = fixture.path("profiles").get(recipeIndex);
            JsonNode observation = executeSafely(recipe, "RECIPE", null, errors);
            originals.put(recipe.path("id").asText(), observation);
            if (++n % 16 == 0) writeProgress(n, errors.size());
        }
        // Bounded probes are declared separately, never masquerade as additional original recipes.
        for (int index : List.of(0, 1)) {
            JsonNode recipe = fixture.path("profiles").get(offset+index);
            JsonNode repeated = executeSafely(recipe, "REPRODUCIBILITY", null, errors);
            JsonNode first = originals.get(recipe.path("id").asText());
            boolean same = "EXECUTED".equals(first.path("executionStatus").asText())
                    && "EXECUTED".equals(repeated.path("executionStatus").asText())
                    && first.path("resultInputHash").equals(repeated.path("resultInputHash"))
                    && first.path("rankedCandidates").equals(repeated.path("rankedCandidates"))
                    && first.path("firstScreen").equals(repeated.path("firstScreen"));
            if (!same) errors.add(recipe.path("id").asText() + ":NON_REPRODUCIBLE");
        }
        for (String range : List.of("OPEN_MAIN_ONLY", "OPEN_ONE_EXTRA"))
            executeSafely(fixture.path("profiles").get(0), "USER_REACTION", range, errors);
        ObjectNode summary = summarize(manifest, errors);
        write("summary.json", summary);
        writeProgress(limit, errors.size());
        // Data/product reachability gaps are findings in summary, not weakened execution assertions.
        assertThat(errors).as("HTTP/protocol/invariant failures; inspect the generated report").isEmpty();
        assertThat(runs).hasSize(limit + 4);
        assertThat(hex(Files.readAllBytes(RECIPES))).isEqualTo(manifest.path("fixtureFileHash").asText());
    }

    private JsonNode executeSafely(JsonNode recipe, String kind, String range, List<String> errors) throws Exception {
        ObjectNode out;
        try { out = execute(recipe, kind, range); }
        catch (AssertionError | Exception e) {
            errors.add(recipe.path("id").asText() + ":" + kind + ":" + e.getClass().getSimpleName());
            out = activeAudit == null ? json.object() : activeAudit;
            out.put("recipeId", recipe.path("id").asText()); out.put("kind", kind);
            out.put("targetMajor", recipe.path("target_major").asText()); out.put("executionStatus", "FAILED");
            out.put("errorClass", e.getClass().getSimpleName());
            out.put("error", Objects.toString(e.getMessage(), "").substring(0, Math.min(3000, Objects.toString(e.getMessage(), "").length())));
        }
        runs.add(out);
        Files.writeString(directory.resolve("runs.jsonl"), json.write(out) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        // Only this harness's synthetic owner is removed. Shared catalogs and unrelated users are never cleared.
        if (users.existsById(OWNER)) deletion.delete(OWNER);
        owner = token(OWNER);
        return out;
    }

    private ObjectNode execute(JsonNode recipe, String kind, String range) throws Exception {
        ObjectNode out = json.object();
        activeAudit = out;
        out.put("recipeId", recipe.path("id").asText()); out.put("kind", kind);
        out.put("recipeInputHash", json.hex(recipe)); out.put("targetMajor", recipe.path("target_major").asText());
        out.put("targetGroup", recipe.path("target_group").asText()); out.put("variant", recipe.path("variant").asText());
        JsonNode started = callPost("", START, 201);
        long id = started.path("sessionId").asLong(), revision = started.path("revision").asLong();
        out.put("sessionId", id); out.set("versions", started.path("versions"));
        verifyVersions(recipe, started.path("versions"));
        Map<String, Integer> interestCount = new TreeMap<>();
        ArrayNode trace = out.putArray("issuedAnswers"), transitions = out.putArray("transitions");
        ObjectNode reaction = null;
        JsonNode fixed = null;
        for (int stage = 1; stage <= 3; stage++) {
            if (stage == 2 && range != null) {
                assertThat(fixed.path("shownCandidates")).isNotEmpty();
                reaction = json.object(); reaction.put("expectedRevision", revision);
                reaction.put("checkpointId", fixed.path("checkpointId").asLong());
                ArrayNode u = reaction.putArray("unexpected"), f = reaction.putArray("unfamiliar");
                for (JsonNode card : fixed.path("shownCandidates")) { u.add(card.path("majorCode").asText()); f.add(card.path("majorCode").asText()); }
                JsonNode saved = callPut(id + "/presentation", reaction);
                revision = saved.path("session").path("revision").asLong(); fixed = saved.path("fixedCandidates");
                out.set("submittedReaction", reaction.deepCopy());
            }
            boolean ready = false;
            for (int batch = 0; batch < 6; batch++) {
                JsonNode next = callPost(id + "/next", revisionBody(revision), 200);
                revision = next.path("revision").asLong();
                for (JsonNode q : next.path("issuedQuestions")) {
                    long issuedId = q.path("issuedId").asLong();
                    var db = jdbc.queryForMap("SELECT snapshot_json,answer_revision FROM exploration_issued_question q LEFT JOIN exploration_answer_revision a ON a.issued_id=q.id AND a.is_current=true WHERE q.id=? AND q.session_id=?", issuedId, id);
                    assertThat(db.get("answer_revision")).isNull();
                    JsonNode snapshot = json.read(db.get("snapshot_json").toString());
                    JsonNode source = snapshot.path("question");
                    assertThat(source.path("stage").asText()).isEqualTo("STAGE_" + stage);
                    assertThat(source.path("module_code").asText()).isNotIn("VALUE", "DECISION_STYLE");
                    String code = choose(recipe, snapshot, interestCount, range);
                    boolean actuallyOffered = false;
                    for (JsonNode option : q.path("options")) if (option.path("code").asText().equals(code)) actuallyOffered = true;
                    assertThat(actuallyOffered).as("No unissued option may be submitted").isTrue();
                    String mutation = UUID.randomUUID().toString();
                    ObjectNode body = json.object(); body.put("expectedRevision", revision);
                    body.put("optionCode", code); body.put("clientMutationId", mutation);
                    JsonNode answer = callPut(id + "/answers/" + issuedId, body);
                    assertThat(answer.path("answerRevision").asInt()).isEqualTo(1);
                    revision = answer.path("revision").asLong();
                    var row = trace.addObject(); row.put("issuedId", issuedId); row.put("questionId", q.path("questionId").asText());
                    row.put("questionVersion", q.path("questionVersion").asText()); row.put("stage", stage);
                    row.put("module", source.path("module_code").asText()); row.put("metric", source.path("metric_code").asText());
                    row.set("comparisonSet", q.path("comparisonSet")); row.set("variantVersion", q.path("optionVariantVersion"));
                    row.put("optionCode", code); row.put("snapshotHash", json.hex(snapshot));
                    row.put("answerRevision", 1); row.put("sessionRevision", revision);
                    questionCoverage.add(q.path("questionId").asText());
                }
                JsonNode detail = getJson("/" + id, owner, 200); requests++;
                assertCaps(detail, stage);
                String action = detail.path("nextAction").asText();
                if (Set.of("ADVANCE", "COMPLETE").contains(action)) { ready = true; break; }
                assertThat(action).isIn("NEXT", "ANSWER");
            }
            assertThat(ready).as("Bounded stage completion").isTrue();
            if (stage < 3) {
                JsonNode advanced = callPost(id + "/advance", revisionBody(revision), 200);
                revision = advanced.path("session").path("revision").asLong();
                assertThat(advanced.path("session").path("stage").asInt()).isEqualTo(stage + 1);
                fixed = advanced.path("fixedCandidates"); transitions.add(advanced);
            }
        }
        JsonNode complete = callPost(id + "/complete", revisionBody(revision), 200);
        long resultId = complete.path("resultId").asLong();
        JsonNode snapshot = json.read(jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=? AND session_id=?", String.class, resultId, id));
        JsonNode repeat = callPost(id + "/complete", revisionBody(revision), 200);
        assertThat(repeat).isEqualTo(complete);
        JsonNode browsing=http(get("/api/exploration/v1/results/"+resultId+"/candidates"),"",200);
        assertThat(browsing.path("items").findValuesAsText("majorCode")).isEqualTo(snapshot.path("rankedCandidates").findValuesAsText("majorCode"));
        assertThat(browsing.path("totalCount").asInt()).isEqualTo(snapshot.path("rankedCandidates").size());
        assertThat(browsing.path("firstScreenCount").asInt()).isEqualTo(snapshot.path("firstScreen").size());
        Set<String> firstCodes=new HashSet<>();snapshot.path("firstScreen").forEach(c->firstCodes.add(c.path("majorCode").asText()));
        for(JsonNode card:browsing.path("items")) {
            assertThat(card.path("name").asText()).isNotBlank();
            assertThat(card.path("isFirstScreen").asBoolean()).isEqualTo(firstCodes.contains(card.path("majorCode").asText()));
        }
        assertThat(browsing.toString()).doesNotContain("basisInputs","sourceHashes","\"total\"","\"score\"","usedInterestAxes");
        out.set("candidateBrowsing",browsing);
        out.put("resultId", resultId); out.put("executionStatus", "EXECUTED");
        out.put("resultInputHash", snapshot.path("inputHash").asText()); out.put("resultSnapshotHash", json.hex(snapshot));
        out.put("bundleHash", snapshot.path("bundleHash").asText()); out.set("sourceHashes", snapshot.path("sourceHashes"));
        out.set("completionInput", snapshot.path("input")); out.set("fixedCandidates", snapshot.path("fixedCandidates"));
        out.set("rankedCandidates", snapshot.path("rankedCandidates")); out.set("firstScreen", snapshot.path("firstScreen"));
        out.set("axes", snapshot.path("axes")); out.set("evidence", snapshot.path("evidenceLevel"));
        out.set("reasonCodes", snapshot.path("reasonCodes")); out.set("presentation", complete.path("presentation"));
        out.set("cards", complete.path("majors")); out.put("completionReason", complete.path("completionReason").asText());
        out.put("resultStatus", complete.path("status").asText());
        String target = recipe.path("target_major").asText();
        boolean group = containsText(snapshot.path("fixedCandidates").path("groupCodes"), recipe.path("target_group").asText());
        boolean pool = containsCode(snapshot.path("rankedCandidates"), target);
        boolean first = containsCode(snapshot.path("firstScreen"), target);
        out.put("targetGroupSelected", group); out.put("targetFixedPool", containsText(snapshot.path("fixedCandidates").path("majorCodes"), target));
        out.put("targetCandidateReached", pool); out.put("targetFirstScreenReached", first);
        ArrayNode more = out.putArray("nonFirstScreenCandidates");
        for (JsonNode m : snapshot.path("rankedCandidates")) if (!containsCode(snapshot.path("firstScreen"), m.path("majorCode").asText())) more.add(m.path("majorCode"));
        out.put("moreResultsMeaning", "AUDIT_SET_DIFFERENCE_ONLY_NOT_A_PUBLIC_MORE_RESULTS_API");
        out.set("diagnosis", diagnose(recipe, snapshot));
        assertThat(snapshot.path("firstScreen").size()).isLessThanOrEqualTo(7);
        assertThat(snapshot.path("fixedCandidates").path("groupCodes").size()).isLessThanOrEqualTo(3);
        if (recipe.path("variant").asText().equals("sparse_interest"))
            for (JsonNode axis : snapshot.path("axes").path("interest")) assertThat(axis.path("validCount").asInt()).isLessThanOrEqualTo(1);
        if (range != null) {
            List<String> actual = new ArrayList<>(); trace.forEach(row -> actual.add(row.path("questionId").asText()));
            assertThat(actual).contains("OPEN-03", "OPEN-04");
            assertThat(complete.path("presentation").path("source").asText()).isEqualTo("USER_REPORTED");
            assertThat(complete.path("presentation").path("unfamiliarRange").asText()).isEqualTo(range);
        }
        return out;
    }

    private String choose(JsonNode recipe, JsonNode snapshot, Map<String, Integer> counts, String range) {
        JsonNode q = snapshot.path("question"); String role = q.path("scoring_role").asText(), module = q.path("module_code").asText();
        List<JsonNode> options = new ArrayList<>(); snapshot.path("options").forEach(options::add);
        java.util.function.Predicate<JsonNode> unknown = o -> o.path("is_unknown").asBoolean() || o.path("is_no_experience").asBoolean();
        if (module.equals("STATE")) return options.stream().filter(o -> o.path("meaning_code").asText().equals("STATE_BROAD"))
                .findFirst().orElseGet(() -> options.stream().filter(unknown).findFirst().orElseThrow()).path("option_code").asText();
        if (q.path("question_id").asText().equals("OPEN-04") && range != null)
            return options.stream().filter(o -> range.equals(o.path("meaning_code").asText())).findFirst().orElseThrow().path("option_code").asText();
        if (Set.of("CANDIDATE_TOPIC", "CANDIDATE_ACTIVITY", "CANDIDATE_LEARNING").contains(role) || module.equals("SUBJECT_CONFIDENCE")) {
            String metric = q.path("metric_code").asText();
            String type = role.equals("CANDIDATE_TOPIC") ? "interest" : role.equals("CANDIDATE_ACTIVITY") ? "activity" : "learning";
            JsonNode target = recipe.path(type + "_targets").path(metric);
            Integer value = target.isNull() ? null : target.isMissingNode() ? 50 : target.intValue();
            if (role.equals("CANDIDATE_TOPIC") && counts.getOrDefault(metric, 0) >= recipe.path("interest_response_count_target").asInt()) value = null;
            final Integer selected = value;
            JsonNode option = options.stream().filter(o -> selected == null ? unknown.test(o) : Integer.toString(selected).equals(o.path("numeric_value").asText())).findFirst().orElseThrow();
            if (role.equals("CANDIDATE_TOPIC") && selected != null) counts.merge(metric, 1, Integer::sum);
            return option.path("option_code").asText();
        }
        return options.stream().filter(unknown).findFirst().orElse(options.get(0)).path("option_code").asText();
    }

    private JsonNode diagnose(JsonNode recipe, JsonNode snapshot) throws Exception {
        ObjectNode out = json.object(); String target = recipe.path("target_major").asText();
        JsonNode mapping = mapper.readTree(Files.readString(Path.of("datasets/exploration/major-mapping.json")));
        JsonNode major = null; for (JsonNode m : mapping.path("majors")) if (m.path("major_code").asText().equals(target)) major = m;
        assertThat(major).isNotNull(); out.set("mapping", major);
        out.set("rankedGroups", snapshot.path("fixedCandidates").path("rankedGroups"));
        String code;
        if (!containsText(snapshot.path("fixedCandidates").path("groupCodes"), recipe.path("target_group").asText())) code = "GROUP_NOT_SELECTED_BY_THESE_RECIPES";
        else if (!containsText(snapshot.path("fixedCandidates").path("majorCodes"), target)) code = "NO_POSITIVE_TOPIC_AT_FREEZE";
        else if (!containsCode(snapshot.path("rankedCandidates"), target)) code = "NO_VALID_POSITIVE_FINAL_CANDIDATE";
        else if (!containsCode(snapshot.path("firstScreen"), target)) code = "RANK_TIE_OR_GROUP_SCREEN_CAP";
        else code = "FIRST_SCREEN_REACHED";
        out.put("code", code);
        List<JsonNode> group = new ArrayList<>(); JsonNode own = null;
        for (JsonNode m : snapshot.path("rankedCandidates")) {
            if (recipe.path("target_group").asText().equals(m.path("group").asText())) group.add(m);
            if (target.equals(m.path("majorCode").asText())) own = m;
        }
        if (own != null) {
            out.set("targetScore", own);
            List<String> tied = new ArrayList<>(); int rank = 0;
            for (int i = 0; i < group.size(); i++) {
                if (target.equals(group.get(i).path("majorCode").asText())) rank = i + 1;
                else if (Double.compare(own.path("total").asDouble(), group.get(i).path("total").asDouble()) == 0) tied.add(group.get(i).path("majorCode").asText());
            }
            out.put("rankWithinGroup", rank); out.set("equalTotalPeersInGroup", json.tree(tied));
            out.put("stableTieBreaker", "majorCode ascending; never invent differences for equivalent profiles");
            double threshold = mapper.readTree(Files.readString(Path.of("datasets/exploration/ruleset.json"))).path("thresholds").path("major_tie_gap_lte").asDouble();
            out.put("optionalThirdGapThreshold", threshold);
            if (rank > 3) out.put("screenLimitReason", tied.isEmpty() ? "PER_GROUP_TOP_TWO_PLUS_OPTIONAL_THIRD" : "STABLE_CODE_TIE_BREAK_WITH_GROUP_CARD_CAP");
            else if (rank == 3 && group.get(1).path("total").asDouble() - own.path("total").asDouble() > threshold)
                out.put("screenLimitReason", "THIRD_MAJOR_GAP_EXCEEDS_THRESHOLD");
            else if (!containsCode(snapshot.path("firstScreen"), target)) out.put("screenLimitReason", "GLOBAL_SEVEN_CARD_CAP");
        }
        return out;
    }

    private ObjectNode summarize(JsonNode manifest, List<String> errors) throws Exception {
        ObjectNode out = json.object(); out.set("manifest", manifest); out.put("runtimeStatus", errors.isEmpty() ? "EXECUTED" : "EXECUTION_FAILURE");
        int originalCount = (int) runs.stream().filter(r -> r.path("kind").asText().equals("RECIPE")).count();
        out.put("originalRecipes", originalCount); out.put("fixtureComplete", originalCount == 376);
        out.put("supplementalProbes", 4); out.put("httpRequests", requests);
        out.set("executionErrors", json.tree(errors)); out.set("issuedQuestionCoverage", json.tree(questionCoverage));
        ArrayNode majors = out.putArray("majors"); int reached = 0, displayed = 0;
        Set<String> targets = new TreeSet<>(); runs.stream().filter(r -> r.path("kind").asText().equals("RECIPE")).forEach(r -> targets.add(r.path("targetMajor").asText()));
        for (String target : targets) {
            ObjectNode m = majors.addObject(); m.put("majorCode", target);
            List<JsonNode> own = runs.stream().filter(r -> r.path("kind").asText().equals("RECIPE") && target.equals(r.path("targetMajor").asText())).toList();
            m.set("candidateRecipes", json.tree(own.stream().filter(r -> r.path("targetCandidateReached").asBoolean()).map(r -> r.path("recipeId").asText()).toList()));
            m.set("firstScreenRecipes", json.tree(own.stream().filter(r -> r.path("targetFirstScreenReached").asBoolean()).map(r -> r.path("recipeId").asText()).toList()));
            boolean candidate = !m.path("candidateRecipes").isEmpty(), screen = !m.path("firstScreenRecipes").isEmpty();
            m.put("candidateReached", candidate); m.put("firstScreenReached", screen);
            m.put("interpretation", candidate ? screen ? "REACHED" : "CANDIDATE_REACHED_DISPLAY_GAP" : "NOT_REACHED_IN_FINITE_RECIPES_NOT_PROOF_OF_IMPOSSIBILITY");
            m.set("observations", json.tree(own.stream().map(r -> Map.of("recipeId", r.path("recipeId").asText(), "diagnosis", r.path("diagnosis").path("code").asText())).toList()));
            if (candidate) reached++; if (screen) displayed++;
        }
        out.put("majorCount", targets.size()); out.put("majorsWithCandidatePath", reached); out.put("majorsWithFirstScreenPath", displayed);
        out.put("candidateGate", originalCount < 376 ? "PARTIAL_AUDIT" : reached == 94 && errors.isEmpty() ? "PASS_FOR_THIS_FINITE_FIXTURE" : "UNRESOLVED");
        out.put("displayGate", displayed == 94 && errors.isEmpty() ? "OBSERVED_FOR_ALL_TARGETS" : "PRODUCT_REVIEW_REQUIRED");
        out.put("releaseReady", false); out.put("dataWeightsOrApprovalsChanged", false);
        return out;
    }

    private void verifyVersions(JsonNode recipe, JsonNode actual) {
        assertThat(actual.path("ruleset").asText()).isEqualTo(recipe.path("ruleset_version").asText());
        assertThat(actual.path("semanticContract").asText()).isEqualTo(recipe.path("semantic_contract_version").asText());
        for (var pair : Map.of("questionCatalog", "question", "optionCatalog", "option", "majorMapping", "major_mapping", "optionVariant", "comparison_option_variant").entrySet())
            assertThat(actual.path(pair.getKey()).asText()).isEqualTo(recipe.path("catalog_versions").path(pair.getValue()).asText());
    }
    private void assertCaps(JsonNode detail, int stage) {
        int[] caps = {0,42,24,12}; int[] seen = new int[4]; Set<Long> ids = new HashSet<>();
        for (JsonNode row : detail.path("issued")) { seen[row.path("stage").asInt()]++; assertThat(ids.add(row.path("question").path("issuedId").asLong())).isTrue(); }
        for (int i=1;i<=3;i++) assertThat(seen[i]).isLessThanOrEqualTo(caps[i]);
        assertThat(ids.size()).isLessThanOrEqualTo(78);
        assertThat(detail.path("session").path("stage").asInt()).isEqualTo(stage);
    }
    private JsonNode callPost(String path, String body, int status) throws Exception { return http(post(path.isEmpty()?API:API+"/"+path),body,status); }
    private JsonNode callPut(String path, JsonNode body) throws Exception { return http(put(API+"/"+path),json.write(body),200); }
    private JsonNode http(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,String body,int expected)throws Exception {
        requests++;
        var result = mvc.perform(request.header("Authorization",owner).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        String payload = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (result.getResponse().getStatus() != expected && result.getRequest().getRequestURI().endsWith("/complete")) {
            if (result.getResolvedException() != null) activeAudit.set("resolvedExceptionTrace", json.tree(Arrays.stream(result.getResolvedException().getStackTrace()).limit(15).map(Object::toString).toList()));
            debugCompletion(activeAudit.path("sessionId").asLong());
        }
        assertThat(result.getResponse().getStatus()).as(result.getRequest().getMethod()+" "+result.getRequest().getRequestURI()+" body="+body+" response="+payload).isEqualTo(expected);
        return mapper.readTree(payload);
    }
    /** Read-only failure evidence; never supplies answers or rewrites a result/catalog to make an audit pass. */
    private void debugCompletion(long id) {
        try {
            var frozen = runtime.load(id); var checkpoint = stages.load(id,"STAGE_2",frozen);
            var values = new ArrayList<com.jinroon.jobe.domain.exploration.catalog.BranchCatalog.Answer>();
            for (var row : jdbc.queryForList("SELECT q.*,a.option_code FROM exploration_issued_question q JOIN exploration_answer_revision a ON a.issued_id=q.id AND a.is_current=true WHERE q.session_id=? ORDER BY q.ordinal", id)) {
                var key = new com.jinroon.jobe.domain.exploration.catalog.BranchCatalog.Key(row.get("question_id").toString(),row.get("question_version").toString(),Objects.toString(row.get("comparison_set"),""),Objects.toString(row.get("variant_version"),""));
                values.add(new com.jinroon.jobe.domain.exploration.catalog.BranchCatalog.Answer(key,row.get("option_code").toString(),true,Boolean.parseBoolean(row.get("effective").toString())));
            }
            ObjectNode input=json.object(); input.set("presentation", stages.presentation(checkpoint,frozen));
            JsonNode assembled=assembler.assemble(id,"SYNTHETIC_DIAGNOSTIC_ONLY","NORMAL",frozen,checkpoint.candidates(),values,input);
            JsonNode javaRoundTrip=json.read(json.write(assembled));
            activeAudit.put("diagnosticJavaRoundTripHash",json.hex(javaRoundTrip));
            ArrayNode javaDifferences=activeAudit.putArray("javaJsonDifferences"); difference("",assembled,javaRoundTrip,javaDifferences);
            JsonNode mysql=json.read(jdbc.queryForObject("SELECT CAST(? AS JSON)",String.class,json.write(assembled)));
            activeAudit.put("diagnosticPreMysqlHash",json.hex(assembled)); activeAudit.put("diagnosticMysqlHash",json.hex(mysql));
            ArrayNode differences=activeAudit.putArray("mysqlJsonDifferences"); difference("",assembled,mysql,differences);
        } catch (Exception e) {
            activeAudit.put("diagnosticException",e.toString());
            activeAudit.set("diagnosticExceptionTrace",json.tree(Arrays.stream(e.getStackTrace()).limit(15).map(Object::toString).toList()));
        }
    }
    private void difference(String path,JsonNode left,JsonNode right,ArrayNode out) {
        if (out.size()>=10 || left.equals(right)) return;
        if(left.isObject() && right.isObject()){left.fieldNames().forEachRemaining(k->difference(path+"/"+k,left.path(k),right.path(k),out));return;}
        if(left.isArray() && right.isArray() && left.size()==right.size()){for(int i=0;i<left.size();i++)difference(path+"/"+i,left.get(i),right.get(i),out);return;}
        ObjectNode row=out.addObject();row.put("path",path);row.set("before",left);row.set("after",right);
    }
    private String revisionBody(long revision) { return json.write(Map.of("expectedRevision",revision)); }
    private static boolean containsCode(JsonNode list,String code) { for(JsonNode row:list) if(code.equals(row.path("majorCode").asText()))return true;return false; }
    private static boolean containsText(JsonNode list,String code) { for(JsonNode row:list) if(code.equals(row.asText()))return true;return false; }
    private void write(String file,JsonNode value)throws Exception { Files.writeString(directory.resolve(file),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value),StandardCharsets.UTF_8); }
    private void writeProgress(int recipes,int errors)throws Exception { write("progress.json",json.tree(Map.of("recipesExecuted",recipes,"runs",runs.size(),"httpRequests",requests,"errors",errors))); }
    private String gitHead()throws Exception { Process p=new ProcessBuilder("git","rev-parse","HEAD").start();String s=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();assertThat(p.waitFor()).isZero();return s; }
    private String sourceHash()throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(Path root:List.of(Path.of("src/main"),Path.of("datasets/exploration")))try(var files=Files.walk(root)){
            for(Path f:files.filter(Files::isRegularFile).sorted().toList()){digest.update(f.toString().replace('\\','/').getBytes(StandardCharsets.UTF_8));digest.update(Files.readAllBytes(f));}}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String hex(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
