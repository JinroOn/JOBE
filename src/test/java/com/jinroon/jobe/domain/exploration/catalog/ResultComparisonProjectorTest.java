package com.jinroon.jobe.domain.exploration.catalog;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.comparison.ResultComparisonProjector;
import com.jinroon.jobe.domain.exploration.service.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Pure projection fixtures, not scoring or reachability claims. Uses the checked-in 32-set/19-pair contract. */
class ResultComparisonProjectorTest {
    private static final ObjectMapper M=new ObjectMapper();
    private static final ExplorationJson JSON=new ExplorationJson(M);
    private static final ExplorationRuntimeCatalog CATALOGS=new ExplorationRuntimeCatalog(null,JSON);
    private static final ResultComparisonProjector PROJECTOR=new ResultComparisonProjector(JSON,CATALOGS);
    private static ExplorationRuntimeCatalog.Frozen frozen;
    private static List<Map<String,String>> skipRows;
    @BeforeAll static void load()throws Exception {
        Path root=Path.of("datasets/exploration");
        JsonNode rules=M.readTree(Files.readString(root.resolve("ruleset.json"))),mapping=M.readTree(Files.readString(root.resolve("major-mapping.json"))),semantic=M.readTree(Files.readString(root.resolve("semantic-dictionary.json")));
        String questions=Files.readString(root.resolve("questions.csv")),options=Files.readString(root.resolve("options.csv")),variants=Files.readString(root.resolve("option-variants.csv")),skips=Files.readString(root.resolve("comparison-skip-pairs.csv"));
        var branch=new BranchCatalog(rules,mapping,semantic,questions,options,variants,skips);var payload=JSON.object();
        payload.set("mapping",mapping);payload.set("questions",JSON.tree(CsvReader.read(questions)));payload.set("options",JSON.tree(CsvReader.read(options)));payload.set("versions",JSON.object());payload.set("sourceHashes",JSON.object());
        frozen=new ExplorationRuntimeCatalog.Frozen(null,JSON.hex(payload),payload,branch);skipRows=CsvReader.read(skips);
    }
    static Stream<Arguments> skipPairs(){return skipRows.stream().map(r->Arguments.of(r.get("comparison_set"),r.get("major_a"),r.get("major_b")));}
    @ParameterizedTest @MethodSource("skipPairs")
    void allNineteenDeclaredPairsAreSkippedInBothDirections(String set,String a,String b){
        for(var pair:List.of(List.of(a,b),List.of(b,a))) {
            JsonNode result=PROJECTOR.compare(result(pair.get(0),pair.get(1)),frozen,pair.get(0),pair.get(1));
            assertThat(rows(result.path("comparisonSets"),set)).isEmpty();assertThat(rows(result.path("skippedComparisonSets"),set)).hasSize(1);
            assertThat(rows(result.path("skippedComparisonSets"),set).get(0).path("declaredSkipPair").asBoolean()).isTrue();
        }
    }
    @Test void everySetWithDistinctMajorMembershipHasFrozenDescriptionsAndNoFabricatedAnswers(){
        int sets=0;
        for(var variant:frozen.branch().variants().values()) {
            var majors=frozen.branch().scoring().majors().stream().map(m->m.majorCode()).filter(m->frozen.branch().comparison(m).sets().contains(variant.code())).toList();
            String a=null,b=null;
            outer:for(String left:majors)for(String right:majors)if(!frozen.branch().comparison(left).memberships().get(variant.code()).equals(frozen.branch().comparison(right).memberships().get(variant.code()))){a=left;b=right;break outer;}
            assertThat(a).as(variant.code()).isNotNull();JsonNode panel=rows(PROJECTOR.compare(result(a,b),frozen,a,b).path("comparisonSets"),variant.code()).get(0);
            assertThat(panel.path("focusResponses").isEmpty()).isTrue();assertThat(panel.path("responseState").asText()).isEqualTo("NOT_ANSWERED");
            for(JsonNode direction:panel.path("leftDirections"))assertThat(direction.path("descriptions").size()).isEqualTo(variant.questions().size());
            sets++;
        }
        assertThat(sets).isEqualTo(32);
    }
    @Test void noCommonSetIsReportedWithoutInventingComparisonDirections(){
        JsonNode response=PROJECTOR.compare(result("MAJOR_ROW_2","MAJOR_ROW_133"),frozen,"MAJOR_ROW_2","MAJOR_ROW_133");
        assertThat(response.path("comparisonState").asText()).isEqualTo("NO_SHARED_COMPARISON_SET");assertThat(response.path("comparisonSets").isEmpty()).isTrue();
    }
    @Test void optionalUnknownResponseIsPreservedWithoutDirectionOrScore(){
        var result=result("MAJOR_ROW_2","MAJOR_ROW_3");addAnswer(result,"CMP-SPS-01","CMP_HISTORY__UNSURE",true);
        JsonNode row=PROJECTOR.compare(result,frozen,"MAJOR_ROW_2","MAJOR_ROW_3").path("comparisonSets").get(0).path("focusResponses").get(0);
        assertThat(row.path("uncertain").asBoolean()).isTrue();assertThat(row.path("selectedDirection").isNull()).isTrue();assertThat(row.path("answerText").asText()).isEqualTo("아직 판단하기 어렵다");
    }
    @Test void inactiveResponsesAreExcludedAndActiveFocusDoesNotPickAWinner(){
        var result=result("MAJOR_ROW_2","MAJOR_ROW_3");addAnswer(result,"CMP-SPS-01","CMP_HISTORY__DOCUMENT_HISTORY",false);addAnswer(result,"CMP-SPS-02","CMP_HISTORY__ARCHAEOLOGY_FIELD",true);
        JsonNode response=PROJECTOR.compare(result,frozen,"MAJOR_ROW_2","MAJOR_ROW_3"),focus=response.path("comparisonSets").get(0).path("focusResponses");
        assertThat(focus.size()).isEqualTo(1);assertThat(focus.get(0).path("selectedDirection").asText()).isEqualTo("ARCHAEOLOGY_FIELD");assertThat(response.has("winner")).isFalse();assertThat(response.path("scoreEffect").asInt()).isZero();
    }
    @Test void missingOrWrongAnswerSnapshotCannotBecomeAnEmptySuccessfulComparison(){
        var result=result("MAJOR_ROW_2","MAJOR_ROW_3");addAnswer(result,"CMP-SPS-01","CMP_HISTORY__DOCUMENT_HISTORY",true);
        ((ObjectNode)result.path("input").path("questions").get(0)).put("snapshotHash","corrupt");
        assertThatThrownBy(()->PROJECTOR.compare(result,frozen,"MAJOR_ROW_2","MAJOR_ROW_3")).isInstanceOf(com.jinroon.jobe.domain.exploration.api.ExplorationApiException.class);
    }
    @Test void comparingDoesNotModifyMappingAxisOrderInputOrNumericScores(){
        var result=result("MAJOR_ROW_2","MAJOR_ROW_3");((ObjectNode)result.path("rankedCandidates").get(0)).put("finalScore",123.456);
        String before=JSON.write(result),mapping=JSON.write(frozen.payload());JsonNode response=PROJECTOR.compare(result,frozen,"MAJOR_ROW_2","MAJOR_ROW_3");
        assertThat(JSON.write(result)).isEqualTo(before);assertThat(JSON.write(frozen.payload())).isEqualTo(mapping);
        assertThat(response.path("left").path("activityAxes")).isEqualTo(frozen.payload().path("mapping").path("majors").get(0).path("activity_axes"));
        assertThat(response.toString()).doesNotContain("123.456","finalScore");
    }
    private static ObjectNode result(String a,String b){var result=JSON.object();result.put("sessionId",1);result.put("runMode","DEV");result.putArray("rankedCandidates").addObject().put("majorCode",a);result.withArray("rankedCandidates").addObject().put("majorCode",b);result.putObject("input").putArray("questions");return result;}
    private void addAnswer(ObjectNode result,String q,String code,boolean effective){var key=frozen.branch().key(frozen.branch().question(q),"CMP-HISTORY");var row=((ObjectNode)result.path("input")).withArray("questions").addObject();row.set("key",JSON.tree(key));row.put("effective",effective);row.put("optionCode",code);row.put("snapshotHash",JSON.hex(CATALOGS.questionSnapshot(frozen,key)));}
    private static List<JsonNode> rows(JsonNode rows,String set){List<JsonNode> out=new ArrayList<>();for(JsonNode row:rows)if(row.path("comparisonSet").asText().equals(set))out.add(row);return out;}
}
