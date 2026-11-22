package com.jinroon.jobe.domain.exploration.display;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.domain.exploration.sharing.SharedResultProjector;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExplorationResultDisplayTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExplorationJson json = new ExplorationJson(mapper);
    private final ResultDisplayCatalog catalog = new ResultDisplayCatalog(mapper);
    private final ExplorationResultDisplay display = new ExplorationResultDisplay(json, catalog);
    ExplorationResultDisplayTest() throws Exception {}

    @Test void labelsCoverEveryPinnedGroupAndScoringMetricWithoutInventingApproval() throws Exception {
        JsonNode semantic = mapper.readTree(Files.readString(Path.of("datasets/exploration/semantic-dictionary.json")));
        JsonNode wording = catalog.snapshot();
        for (JsonNode code : semantic.path("groups")) {
            assertThat(wording.path("groups").path(code.asText()).path("name").asText()).isNotBlank();
            assertThat(wording.path("groups").path(code.asText()).path("summary").asText()).contains("탐색");
        }
        for (String field : List.of("interest_axes", "activity_axes", "learning_sets"))
            for (JsonNode code : semantic.path(field)) assertThat(wording.path("metrics").path(code.asText()).asText()).isNotBlank();
        assertThat(wording.path("scope").asText()).isEqualTo("DEV");
        assertThat(wording.path("domainReviewStatus").asText()).isEqualTo("PENDING");
        // A changed wording file must receive a new version; legacy projections must remain reproducible.
        assertThat(wording.path("version").asText()).isEqualTo(ResultDisplayCatalog.VERSION);
        assertThat(json.hex(wording)).isEqualTo("bb47416d0fffa35704761e417f78538a075227654b3d6310fbf9265731f95880");
        JsonNode legacy = catalog.legacySnapshot();
        assertThat(legacy.path("version").asText()).isEqualTo(ResultDisplayCatalog.LEGACY_VERSION);
        assertThat(json.hex(legacy)).isEqualTo("6986cb77a34b4a01000a43fb805c866140137bfc0ea89deda697faea645e8a91");
    }

    @Test void groupLabelsDescribeTheMajorsMappedToEachGroup() throws Exception {
        JsonNode mapping = mapper.readTree(Files.readString(Path.of("datasets/exploration/major-mapping.json")));
        Set<String> medMajors = new TreeSet<>();
        for (JsonNode major : mapping.path("majors"))
            if ("MED".equals(major.path("exploration_group").asText())) medMajors.add(major.path("major_name").asText());
        assertThat(medMajors).contains("문예창작학과", "미디어커뮤니케이션학과", "광고홍보학과");
        assertThat(medMajors).doesNotContain("간호학과", "의학과", "약학과");
        JsonNode med = catalog.snapshot().path("groups").path("MED");
        assertThat(med.path("name").asText()).isEqualTo("미디어·콘텐츠");
        assertThat(med.path("summary").asText()).contains("미디어").doesNotContain("건강", "돌봄");
        // V1_0 stays byte-identical for results sealed before the correction.
        assertThat(catalog.legacySnapshot().path("groups").path("MED").path("name").asText()).isEqualTo("보건·의료");
    }

    @Test void legacyProjectionWithoutSealedDisplayKeepsV10Wording() {
        ObjectNode result = result(2, false);
        result.remove("display");
        JsonNode view = display.project(result);
        assertThat(view.path("version").asText()).isEqualTo(ResultDisplayCatalog.LEGACY_VERSION);
        assertThat(view.path("source").asText()).isEqualTo("SEALED_RESULT_PROJECTION");
    }

    @ParameterizedTest @CsvSource({"0,false,NO_EVIDENCE", "1,false,LOW_EVIDENCE", "2,false,CONFIRMED", "2,true,LOW_EVIDENCE"})
    void cardAndGroupEvidenceUseOnlyActuallyUsedInterestAxes(int valid, boolean inconsistent, String expected) {
        ObjectNode result = result(valid, inconsistent);
        JsonNode view = display.project(result);
        assertThat(view.path("majors").get(0).path("evidenceLevel").asText()).isEqualTo(expected);
        assertThat(view.path("groups").get(0).path("evidenceLevel").asText()).isEqualTo(expected);
        // An unrelated no-evidence axis is only a recheck suggestion, not a reason to downgrade this card.
        assertThat(view.path("recheckAxes").toString()).contains("INT_SCI", "자연과학");
    }

    @Test void projectionDoesNotMutateScoresOrderStatusOrSourceAndIsDeterministic() {
        ObjectNode source = result(2, false); String original = json.write(source);
        JsonNode first = display.project(source), second = display.project(source);
        assertThat(first).isEqualTo(second); assertThat(json.write(source)).isEqualTo(original);
        assertThat(first.path("majors").get(0).path("majorCode").asText()).isEqualTo("M1");
        assertThat(first.path("majors").get(0).path("reasons").size()).isEqualTo(3);
        assertThat(first.path("majors").get(0).path("reasons").toString()).contains("인문", "ACTIVITY_EVIDENCE_NOT_USED", "LEARNING_EVIDENCE_NOT_USED");
        assertThat(first.path("majors").get(0).path("collapsed").asBoolean()).isFalse();
        assertThat(first.path("majors").get(0).path("collapseReasonCode").asText()).isEqualTo("PRESENTATION_NOT_COLLECTED");
        assertThat(first.toString()).doesNotContain("total", "score", "topic", "rankedCandidates");
        assertThat(first.path("cautionAvailability").asText()).isEqualTo("UNAVAILABLE");
    }

    @Test void aRealZeroActivityAnswerIsPreservedAsUsedEvidenceWithoutClaimingPositiveFit() {
        ObjectNode result = result(2, false);
        ((ArrayNode) result.path("majorSummaries").get(0).path("usedActivityAxes")).add("ACT_FLD");
        JsonNode reason = display.project(result).path("majors").get(0).path("reasons").get(1);
        assertThat(reason.path("reasonCode").asText()).isEqualTo("ACTIVITY_RESPONSES_USED");
        assertThat(reason.path("text").asText()).contains("현장", "참고").doesNotContain("잘 맞", "뛰어", "높은");
    }

    @ParameterizedTest @CsvSource({"OPEN_MAIN_ONLY,0", "OPEN_ONE_EXTRA,1", "OPEN_MULTIPLE,3", "OPEN_UNSURE,3", "NOT_ANSWERED,3"})
    void unfamiliarRangeOnlyControlsUnfoldingAndNeverRemovesOrReordersCandidates(String range, int unfolded) {
        ObjectNode source = result(2, false);
        for (String code : List.of("M2", "M3")) {
            ObjectNode copy = source.path("majorSummaries").get(0).deepCopy(); copy.put("majorCode", code);
            ((ArrayNode) source.path("majorSummaries")).add(copy);
        }
        ObjectNode reaction = source.putObject("presentation"); reaction.put("source", "USER_REPORTED");
        reaction.putArray("unfamiliar").add("M1").add("M2").add("M3"); reaction.putArray("unexpected").add("M2");
        reaction.put("unfamiliarRange", range); reaction.put("unexpectedAction", "OPEN_COMPARE");
        String original = json.write(source); JsonNode view = display.project(source);
        int opened = 0; List<String> codes = new ArrayList<>();
        for (JsonNode card : view.path("majors")) { if (!card.path("collapsed").asBoolean()) opened++; codes.add(card.path("majorCode").asText()); }
        assertThat(opened).isEqualTo(unfolded);
        assertThat(codes).containsExactly("M1", "M2", "M3");
        assertThat(view.path("majors").get(1).path("unexpectedAction").asText()).isEqualTo("OPEN_COMPARE");
        assertThat(view.path("majors").get(0).has("unexpectedAction")).isFalse();
        assertThat(json.write(source)).isEqualTo(original);
    }

    @Test void noUnfamiliarityIsInferredFromMissingReactionOrAnOpennessAnswerAlone() {
        ObjectNode source = result(2, false);
        source.putObject("presentation").put("source", "NOT_COLLECTED").put("unfamiliarRange", "OPEN_MAIN_ONLY");
        JsonNode card = display.project(source).path("majors").get(0);
        assertThat(card.path("collapsed").asBoolean()).isFalse();
        assertThat(card.path("collapseReasonCode").asText()).isEqualTo("PRESENTATION_NOT_COLLECTED");
    }

    @ParameterizedTest @CsvSource({
        "REVIEWED,APPROVED_FOR_IMPLEMENTATION,PENDING,PROPOSED,true,1",
        "ACTIVE,APPROVED_FOR_IMPLEMENTATION,APPROVED,PROPOSED,true,1",
        "DRAFT,PENDING,PENDING,PROPOSED,true,0",
        "REVIEWED,REJECTED,PENDING,PROPOSED,true,0",
        "REVIEWED,APPROVED_FOR_IMPLEMENTATION,REJECTED,PROPOSED,true,0",
        "ARCHIVED,APPROVED_FOR_IMPLEMENTATION,PENDING,PROPOSED,true,0",
        "REVIEWED,APPROVED_FOR_IMPLEMENTATION,PENDING,NOT_PROPOSED,true,0",
        "REVIEWED,APPROVED_FOR_IMPLEMENTATION,PENDING,PROPOSED,false,0"
    })
    void onlyDeclaredDevelopmentEligibleCautionTagsAreExposed(String content, String implementation, String domain,
                                                              String proposal, boolean declared, int expected) {
        ObjectNode bundle = bundle();
        if (!declared) ((ObjectNode)bundle.path("mapping").path("majors").get(0)).putArray("approved_caution_tags");
        ObjectNode tag = (ObjectNode)bundle.path("cautionTags").get(0);
        tag.put("contentStatus", content); tag.put("implementationReviewStatus", implementation);
        tag.put("domainReviewStatus", domain); tag.put("proposalStatus", proposal);
        JsonNode view = display.freeze(result(2, false), bundle);
        assertThat(view.path("cautions").size()).isEqualTo(expected);
        if (expected == 1) {
            assertThat(view.path("cautions").get(0).path("text").asText()).contains("확인");
            assertThat(view.path("cautions").get(0).path("checkAction").asText()).isNotBlank();
            assertThat(view.path("cautions").get(0).path("scope").asText()).isEqualTo("DEV");
        }
    }

    @Test void missingApprovalEvidenceCannotBecomeGuidanceAndGuidanceIsCappedAtTwo() {
        ObjectNode bundle = bundle();
        ((ObjectNode)bundle.path("cautionTags").get(0)).put("evidence", "");
        assertThat(display.freeze(result(2, false), bundle).path("cautions").isEmpty()).isTrue();
        bundle = bundle();
        for (String code : List.of("CAUTION_SCIENCE", "CAUTION_PRACTICE")) {
            ((ArrayNode)bundle.path("mapping").path("majors").get(0).path("approved_caution_tags")).add(code);
            ObjectNode copy = bundle.path("cautionTags").get(0).deepCopy(); copy.put("tagCode", code);
            ((ArrayNode)bundle.path("cautionTags")).add(copy);
        }
        assertThat(display.freeze(result(2, false), bundle).path("cautions").size()).isEqualTo(2);
    }

    @Test void frozenDisplayRemainsStableWithoutAnyCurrentBundleAndReturnsADefensiveCopy() {
        ObjectNode source = result(2, false); source.set("display", display.freeze(source, bundle()));
        JsonNode first = display.project(source); ((ObjectNode)first.path("groups").get(0)).put("name", "changed");
        assertThat(display.project(source).path("groups").get(0).path("name").asText()).isEqualTo("인문");
        assertThat(display.project(source).path("source").asText()).isEqualTo("COMPLETION_SNAPSHOT");
    }

    @Test void completionUsesThePinnedWordingRatherThanCurrentCatalogLabels() {
        ObjectNode bundle = bundle();
        ((ObjectNode)bundle.path("displayContent").path("groups").path("HUM")).put("name", "고정된 시험 이름");
        JsonNode view = display.freeze(result(2, false), bundle);
        assertThat(view.path("groups").get(0).path("name").asText()).isEqualTo("고정된 시험 이름");
        assertThat(catalog.snapshot().path("groups").path("HUM").path("name").asText()).isEqualTo("인문");
    }

    @Test void unknownOrAbsentEvidenceDoesNotCreateCandidatesOrCautions() {
        ObjectNode source = result(0, false); source.putArray("majorSummaries"); source.putArray("groupSummaries"); source.putArray("rankedCandidates");
        JsonNode view = display.freeze(source, bundle());
        assertThat(view.path("groups").isEmpty()).isTrue(); assertThat(view.path("majors").isEmpty()).isTrue();
        assertThat(view.path("cautions").isEmpty()).isTrue(); assertThat(view.path("recheckAxes").size()).isEqualTo(2);
    }

    @Test void currentAndLegacySharingHaveSeparateAllowlistsAndNeverIncludePrivateGuidance() {
        var shares = new SharedResultProjector(json, display); ObjectNode source = result(2, false);
        source.set("display", display.freeze(source, bundle()));
        JsonNode legacy = shares.project(source, null, SharedResultProjector.LEGACY_CONTRACT);
        JsonNode current = shares.project(source, null);
        assertThat(shares.allowed(legacy)).isTrue(); assertThat(shares.allowed(current)).isTrue();
        assertThat(legacy.path("groups").get(0).size()).isEqualTo(1);
        assertThat(current.path("groups").get(0).path("name").asText()).isEqualTo("인문");
        assertThat(current.toString()).doesNotContain("cautions", "interestEvidence", "evidenceLevel", "recheckAxes", "preparationResponses", "usedInterestAxes");
        ((ObjectNode)current.path("groups").get(0)).put("privateAnswer", "synthetic");
        assertThat(shares.allowed(current)).isFalse();
    }

    private ObjectNode result(int valid, boolean inconsistent) {
        ObjectNode result = json.object(); result.put("status", "READY");
        var summary = result.putArray("majorSummaries").addObject(); summary.put("majorCode", "M1"); summary.put("majorName", "고고학과"); summary.put("groupCode", "HUM");
        summary.putArray("usedInterestAxes").add("INT_HUM"); summary.putArray("usedActivityAxes"); summary.putArray("usedLearningSets");
        var group = result.putArray("groupSummaries").addObject(); group.put("groupCode", "HUM"); group.putArray("majorCodes").add("M1");
        var candidate = result.putArray("rankedCandidates").addObject(); candidate.put("majorCode", "M1"); candidate.put("total", 75);
        var axis = candidate.putObject("evidence").putObject("interest").putObject("INT_HUM"); axis.put("validCount", valid); axis.put("inconsistent", inconsistent); axis.put("score", 75);
        var interest = result.putObject("axes").putObject("interest"); interest.set("INT_HUM", axis.deepCopy());
        interest.putObject("INT_SCI").put("validCount", 0).put("inconsistent", false);
        return result;
    }

    private ObjectNode bundle() {
        ObjectNode bundle = json.object(); bundle.set("displayContent", catalog.snapshot());
        var major = bundle.putObject("mapping").putArray("majors").addObject(); major.put("major_code", "M1"); major.putArray("approved_caution_tags").add("CAUTION_MATH");
        var tag = bundle.putArray("cautionTags").addObject(); tag.put("majorCode", "M1"); tag.put("tagCode", "CAUTION_MATH"); tag.put("proposalStatus", "PROPOSED");
        tag.put("contentStatus", "REVIEWED"); tag.put("implementationReviewStatus", "APPROVED_FOR_IMPLEMENTATION"); tag.put("domainReviewStatus", "PENDING"); tag.put("evidence", "synthetic fixture");
        return bundle;
    }
}
