package com.jinroon.jobe.domain.exploration.scoring;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.branching.CandidateEngine;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import static com.jinroon.jobe.domain.exploration.scoring.ExplorationScoreCalculator.*;

class ExplorationScoreCalculatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DATA = Path.of("datasets/exploration");
    private static ScoringCatalog catalog;
    private static ExplorationScoreCalculator calculator;
    @BeforeAll static void load() throws Exception {
        catalog = ScoringCatalog.fromSnapshots(read("ruleset.json"), read("major-mapping.json"), read("semantic-dictionary.json"));
        calculator = catalog.calculator();
    }
    private static JsonNode read(String name) throws Exception { return JSON.readTree(Files.readString(DATA.resolve(name))); }
    private static Response response(String id, Role role, String metric, Integer value) {
        return new Response(id, role, metric, value, true, true);
    }
    private static MajorProfile profile(InterestStructure structure) {
        return new MajorProfile("MAJOR_ROW_TEST", "HUM", structure, "INT_HUM",
                structure == InterestStructure.SINGLE ? "INT_HUM" : "INT_SOC",
                List.of("ACT_CRE", "ACT_DES", "ACT_EDU"), List.of("LEARN_HUM"));
    }
    private static MajorScore score(InterestStructure structure, List<Response> responses) {
        return calculator.score(profile(structure), calculator.aggregate(responses));
    }
    private static List<Response> input(Integer primary, Integer secondary, Integer a, Integer b, Integer c, Integer learning) {
        return List.of(response("I1",Role.CANDIDATE_TOPIC,"INT_HUM",primary),
                response("I2",Role.CANDIDATE_TOPIC,"INT_SOC",secondary),
                response("A1",Role.CANDIDATE_ACTIVITY,"ACT_CRE",a),
                response("A2",Role.CANDIDATE_ACTIVITY,"ACT_DES",b),
                response("A3",Role.CANDIDATE_ACTIVITY,"ACT_EDU",c),
                response("L1",Role.CANDIDATE_LEARNING,"LEARN_HUM",learning));
    }

    @Test void affirmativeInterestGateKeepsExistingThresholdAndIgnoresIneffectiveResponses() throws Exception {
        var current = ScoringCatalog.fromSnapshots(read("ruleset-v1-6.json"), read("major-mapping.json"), read("semantic-dictionary.json"));
        var engine = new CandidateEngine(current, 5, 5);
        List<Response> neutral = List.of(
                response("neutral", Role.CANDIDATE_TOPIC, "INT_HUM", 50),
                response("unknown", Role.CANDIDATE_TOPIC, "INT_HUM", null),
                new Response("superseded", Role.CANDIDATE_TOPIC, "INT_HUM", 100, false, true),
                new Response("excluded", Role.CANDIDATE_TOPIC, "INT_HUM", 100, true, false),
                response("activity", Role.CANDIDATE_ACTIVITY, "ACT_CRE", 100),
                response("learning", Role.CANDIDATE_LEARNING, "LEARN_HUM", 100));
        assertThat(engine.freeze(neutral).majorCodes()).isEmpty();
        assertThat(new CandidateEngine(catalog, 5, 5).freeze(neutral).majorCodes()).isNotEmpty();

        List<Response> mixed = List.of(
                response("positive", Role.CANDIDATE_TOPIC, "INT_HUM", 75),
                response("negative", Role.CANDIDATE_TOPIC, "INT_HUM", 25));
        var fixed = engine.freeze(mixed);
        assertThat(fixed.majorCodes()).isNotEmpty();
        assertThat(engine.rerank(fixed, mixed).candidates()).isNotEmpty().allSatisfy(major ->
                assertThat(major.topic()).isEqualTo(50));
        assertThat(engine.rerank(fixed, neutral).candidates()).isEmpty();
    }

    static Stream<Arguments> activityCases() throws Exception {
        List<Arguments> cases = new ArrayList<>();
        for (JsonNode row : JSON.readTree(Files.readString(Path.of("datasets/exploration-validation/activity-scoring-cases.json"))).path("cases"))
            cases.add(Arguments.of(row.path("id").asText(), row));
        return cases.stream();
    }
    @ParameterizedTest(name="D04 {0}") @MethodSource("activityCases")
    void matchesIndependentD04Cases(String id, JsonNode row) {
        JsonNode values = row.path("scores");
        MajorScore result = score(InterestStructure.PRIMARY, input(100,75,integer(values.get(0)),integer(values.get(1)),integer(values.get(2)),null));
        if (row.path("expected").isNull()) assertThat(result.activity()).isNull();
        else assertThat(result.activity()).isCloseTo(row.path("expected").doubleValue(), within(1e-10));
    }
    private static Integer integer(JsonNode node) { return node.isNull() ? null : node.intValue(); }

    @ParameterizedTest @CsvSource({"SINGLE,100,93", "PRIMARY,70,71.4", "HYBRID,55,60.6"})
    void interestStructuresAndNoLearningWeights(InterestStructure structure, double topic, double total) {
        MajorScore result = score(structure, input(100,0,75,75,75,null));
        assertThat(result.topic()).isCloseTo(topic,within(1e-12));
        assertThat(result.total()).isCloseTo(total,within(1e-12));
        assertThat(result.usedInterestAxes()).hasSize(structure == InterestStructure.SINGLE ? 1 : 2);
    }
    @Test void learningUsesValidResponseMeanAndRecordsCount() {
        List<Response> responses = new ArrayList<>(input(100,0,75,75,75,0));
        responses.add(response("L2",Role.CANDIDATE_LEARNING,"LEARN_HUM",100));
        MajorScore result = score(InterestStructure.PRIMARY,responses);
        assertThat(result.learning()).isEqualTo(50);
        assertThat(result.total()).isCloseTo(69.4,within(1e-12));
        assertThat(result.evidence().learning().get("LEARN_HUM").validCount()).isEqualTo(2);
    }
    @Test void missingComponentsRenormalizeWithoutMovingWeightsAndZeroLearningIsValid() {
        assertThat(score(InterestStructure.PRIMARY,input(null,75,null,null,null,null)).total()).isEqualTo(75);
        MajorScore result=score(InterestStructure.PRIMARY,input(100,0,null,null,null,0));
        assertThat(result.total()).isCloseTo(43.4/0.72,within(1e-12));
        assertThat(result.usedLearningSets()).containsExactly("LEARN_HUM");
    }
    @Test void noInterestNeverBecomesActivityOnlyRecommendation() {
        MajorScore result=score(InterestStructure.PRIMARY,input(null,null,100,100,100,100));
        assertThat(result.total()).isNull();
        assertThat(result.positiveTopicEvidence()).isFalse();
    }
    @Test void interestEvidenceAndConflictsKeepScoresUnpenalized() {
        List<Response> responses=new ArrayList<>(input(null,25,null,null,null,null));
        responses.add(response("confirm",Role.CANDIDATE_TOPIC,"INT_HUM",75));
        AxisScore single=calculator.aggregate(responses).interest().get("INT_HUM");
        assertThat(single.interestEvidence()).isEqualTo(InterestEvidence.LOW_EVIDENCE);
        responses.add(response("screen",Role.CANDIDATE_TOPIC,"INT_HUM",25));
        Axes axes=calculator.aggregate(responses);
        AxisScore confirmed=axes.interest().get("INT_HUM");
        assertThat(confirmed.score()).isEqualTo(50);
        assertThat(confirmed.validCount()).isEqualTo(2);
        assertThat(confirmed.inconsistent()).isTrue();
        assertThat(confirmed.interestEvidence()).isEqualTo(InterestEvidence.CONFIRMED);
        assertThat(axes.interest().get("INT_ENG").interestEvidence()).isEqualTo(InterestEvidence.NO_EVIDENCE);
        assertThat(calculator.score(profile(InterestStructure.PRIMARY),axes).topic()).isCloseTo(42.5,within(1e-12));
    }
    @Test void gapsBelowThresholdDoNotFlagInconsistency() {
        Axes axes=calculator.aggregate(List.of(response("a",Role.CANDIDATE_TOPIC,"INT_HUM",25),response("b",Role.CANDIDATE_TOPIC,"INT_HUM",50)));
        assertThat(axes.interest().get("INT_HUM").inconsistent()).isFalse();
    }
    @Test void repeatedResponsesOnOneActivityAxisDoNotMeetTwoAxisMinimum() {
        MajorScore result=score(InterestStructure.PRIMARY,List.of(response("I",Role.CANDIDATE_TOPIC,"INT_HUM",100),
                response("a",Role.CANDIDATE_ACTIVITY,"ACT_CRE",100),response("b",Role.CANDIDATE_ACTIVITY,"ACT_CRE",0)));
        assertThat(result.evidence().activity().get("ACT_CRE").score()).isEqualTo(50);
        assertThat(result.activity()).isNull();
        assertThat(result.usedActivityAxes()).isEmpty();
        assertThat(result.total()).isEqualTo(100);
    }
    @ParameterizedTest @EnumSource(value=Role.class,names={"CANDIDATE_TOPIC","CANDIDATE_ACTIVITY","CANDIDATE_LEARNING"},mode=EnumSource.Mode.EXCLUDE)
    void everyNonScoringRoleIsExcludedEvenWithNumericValue(Role role) {
        List<Response> base=input(100,75,25,100,75,75);
        List<Response> changed=new ArrayList<>(base);
        changed.add(response("unscored",role,"INT_HUM",0));
        assertThat(score(InterestStructure.PRIMARY,changed)).isEqualTo(score(InterestStructure.PRIMARY,base));
    }
    @Test void excludedAndSupersededAnswersDoNotAffectCountsOrScores() {
        Response active=response("I",Role.CANDIDATE_TOPIC,"INT_HUM",75);
        List<Response> responses=List.of(active,new Response("I",Role.CANDIDATE_TOPIC,"INT_HUM",0,false,true),
                new Response("excluded",Role.CANDIDATE_TOPIC,"INT_HUM",100,true,false));
        assertThat(calculator.aggregate(responses).interest().get("INT_HUM")).isEqualTo(new AxisScore(75.0,1,false));
    }
    @Test void minimumPositiveEvidenceUsesAnAxisNotTheWeightedTopicOrActivity() {
        assertThat(score(InterestStructure.PRIMARY,input(0,50,0,0,0,null)).positiveTopicEvidence()).isTrue();
        assertThat(score(InterestStructure.PRIMARY,input(25,25,100,100,100,100)).positiveTopicEvidence()).isFalse();
    }
    @Test void rejectsInvalidScaleMetricDuplicateQuestionAndBadProfile() {
        assertThatThrownBy(()->calculator.aggregate(List.of(response("a",Role.CANDIDATE_TOPIC,"INT_HUM",49)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->calculator.aggregate(List.of(response("a",Role.CANDIDATE_TOPIC,"ACT_CRE",null)))).isInstanceOf(IllegalArgumentException.class);
        Response one=response("same",Role.CANDIDATE_TOPIC,"INT_HUM",50);
        assertThatThrownBy(()->calculator.aggregate(List.of(one,one))).isInstanceOf(IllegalArgumentException.class);
        MajorProfile bad=new MajorProfile("bad","HUM",InterestStructure.PRIMARY,"INT_HUM","INT_HUM",List.of("ACT_CRE","ACT_CRE","ACT_DES"),List.of());
        assertThatThrownBy(()->calculator.score(bad,calculator.aggregate(List.of()))).isInstanceOf(IllegalArgumentException.class);
        MajorProfile duplicate=new MajorProfile("bad","HUM",InterestStructure.PRIMARY,"INT_HUM","INT_SOC",List.of("ACT_CRE","ACT_CRE","ACT_DES"),List.of());
        assertThatThrownBy(()->calculator.score(duplicate,calculator.aggregate(List.of()))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void stableCodeOrderingNullsLastAndNoIntermediateRounding() {
        MajorProfile a=profile(InterestStructure.PRIMARY);
        MajorProfile b=new MajorProfile("AAA","HUM",a.structure(),a.primary(),a.secondary(),a.activityAxes(),a.learningSets());
        MajorProfile absent=new MajorProfile("NULL","HUM",a.structure(),"INT_ENG","INT_DAT",a.activityAxes(),a.learningSets());
        List<Response> input=input(100,75,null,100,75,75);
        List<MajorScore> result=calculator.scoreAndSort(List.of(a,absent,b),input);
        assertThat(result).extracting(MajorScore::majorCode).containsExactly("AAA",a.majorCode(),"NULL");
        List<Response> reversed=new ArrayList<>(input); Collections.reverse(reversed);
        assertThat(calculator.scoreAndSort(List.of(b,a,absent),reversed)).isEqualTo(result);
        assertThat(result.get(0).activity()).isEqualTo(90);
        assertThatThrownBy(()->result.get(0).evidence().interest().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void catalogRejectsMismatchedVersionsAndUnsupportedPolicies() throws Exception {
        ObjectNode rules=(ObjectNode)read("ruleset.json");
        rules.put("semantic_contract_version","OTHER");
        assertThatThrownBy(()->ScoringCatalog.fromSnapshots(rules,read("major-mapping.json"),read("semantic-dictionary.json"))).isInstanceOf(IllegalArgumentException.class);
        ObjectNode changed=(ObjectNode)read("ruleset.json");
        ((ObjectNode)changed.path("activity_scoring")).put("sort_by_response_score",true);
        assertThatThrownBy(()->ScoringCatalog.fromSnapshots(changed,read("major-mapping.json"),read("semantic-dictionary.json"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void all376RecipesExecuteArithmeticFor94MappingsWithoutClaimingReachability() throws Exception {
        JsonNode fixtures=JSON.readTree(Files.readString(Path.of("datasets/exploration-validation/reachability-profiles.json")));
        assertThat(fixtures.path("runtime_status").asText()).isEqualTo("NOT_EXECUTED");
        assertThat(catalog.majors()).hasSize(94);
        int checked=0;
        for(JsonNode recipe:fixtures.path("profiles")) {
            List<Response> responses=new ArrayList<>();
            for(String kind:List.of("interest","activity","learning")) {
                Role role=switch(kind) {case "interest"->Role.CANDIDATE_TOPIC;case "activity"->Role.CANDIDATE_ACTIVITY;default->Role.CANDIDATE_LEARNING;};
                var fields=recipe.path(kind+"_targets").fields();
                while(fields.hasNext()) {
                    var field=fields.next();
                    int count=kind.equals("interest")?recipe.path("interest_response_count_target").intValue():1;
                    for(int i=0;i<count;i++) responses.add(response(kind+field.getKey()+i,role,field.getKey(),integer(field.getValue())));
                }
            }
            List<MajorScore> scores=calculator.scoreAndSort(catalog.majors(),responses);
            assertThat(scores).hasSize(94);
            assertThat(scores).allSatisfy(s->{if(s.total()!=null)assertThat(s.total()).isBetween(0.0,100.0);});
            List<Response> reversed=new ArrayList<>(responses);Collections.reverse(reversed);
            assertThat(calculator.scoreAndSort(catalog.majors(),reversed)).isEqualTo(scores);
            MajorScore target=scores.stream().filter(s->s.majorCode().equals(recipe.path("target_major").asText())).findFirst().orElseThrow();
            if(recipe.path("variant").asText().equals("baseline")) {
                MajorProfile major=catalog.majors().stream().filter(m->m.majorCode().equals(target.majorCode())).findFirst().orElseThrow();
                double expected=switch(major.structure()){case SINGLE->90.5;case PRIMARY->85.85;case HYBRID->83.525;};
                assertThat(target.total()).as(target.majorCode()).isCloseTo(expected,within(1e-10));
            }
            checked++;
        }
        assertThat(checked).isEqualTo(376);
    }
}
