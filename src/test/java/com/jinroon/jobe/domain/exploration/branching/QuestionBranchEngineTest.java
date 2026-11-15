package com.jinroon.jobe.domain.exploration.branching;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.jinroon.jobe.domain.exploration.catalog.BranchCatalog;
import com.jinroon.jobe.domain.exploration.scoring.ScoringCatalog;
import static com.jinroon.jobe.domain.exploration.catalog.BranchCatalog.*;
import static com.jinroon.jobe.domain.exploration.branching.CandidateEngine.*;
import static com.jinroon.jobe.domain.exploration.branching.QuestionBranchEngine.*;
import static com.jinroon.jobe.domain.exploration.scoring.ExplorationScoreCalculator.*;
import static org.assertj.core.api.Assertions.*;

class QuestionBranchEngineTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Path DATA=Path.of("datasets/exploration");
    private static BranchCatalog catalog;
    private static QuestionBranchEngine engine;
    private static JsonNode json(String name)throws Exception{return JSON.readTree(Files.readString(DATA.resolve(name)));}
    private static String csv(String name)throws Exception{return Files.readString(DATA.resolve(name));}
    @BeforeAll static void load()throws Exception {
        catalog=new BranchCatalog(json("ruleset.json"),json("major-mapping.json"),json("semantic-dictionary.json"),csv("questions.csv"),csv("options.csv"),csv("option-variants.csv"),csv("comparison-skip-pairs.csv"));
        engine=new QuestionBranchEngine(catalog);
    }
    private static Key key(String id){return catalog.key(catalog.question(id),"");}
    private static MajorProfile profile(String code){return catalog.scoring().majors().stream().filter(m->m.majorCode().equals(code)).findFirst().orElseThrow();}
    private static FixedCandidates fixed(String...codes) {
        List<String> groups=Arrays.stream(codes).map(c->profile(c).group()).distinct().toList();
        return new FixedCandidates(groups.stream().map(g->new GroupScore(g,75)).toList(),groups,List.of(codes));
    }
    private static Response metric(String id,String metric,Integer value){return new Response(id,Role.CANDIDATE_TOPIC,metric,value,true,true);}
    private static MajorProfile simple(String code,String group,String interest) {
        return new MajorProfile(code,group,InterestStructure.SINGLE,interest,interest,List.of("ACT_CRE","ACT_DES","ACT_EDU"),List.of("LEARN_HUM"));
    }
    private static CandidateEngine candidateEngine(List<MajorProfile> majors) {
        ScoringCatalog s=catalog.scoring();return new CandidateEngine(new ScoringCatalog(s.rulesetVersion(),s.mappingVersion(),s.semanticVersion(),s.calculator(),majors),5,5);
    }
    private static final class Run {
        final List<Key> history=new ArrayList<>();final List<Answer> answers=new ArrayList<>();
        final Map<String,Integer> targets=new HashMap<>();final Route route;int maxInterestNumeric=Integer.MAX_VALUE;
        Run(Route route){this.route=route;}
        void answer(Collection<Key> keys) {
            for(Key k:keys) {
                if(!history.contains(k))history.add(k);
                Question q=catalog.question(k.questionId());
                boolean interestLimit=q.role()==Role.CANDIDATE_TOPIC&&answers.stream()
                        .filter(a->!a.key().equals(k)&&catalog.question(a.key().questionId()).metric().equals(q.metric())&&catalog.option(a).value()!=null).count()>=maxInterestNumeric;
                Integer target=interestLimit?null:targets.getOrDefault(q.metric(),50);
                Option option;
                if(q.module().equals("STATE"))option=catalog.options(k).stream().filter(o->o.meaning().equals("STATE_"+route)).findFirst().orElseGet(()->catalog.options(k).stream().filter(Option::unknown).findFirst().orElseThrow());
                else if(q.role()==Role.CANDIDATE_TOPIC||q.role()==Role.CANDIDATE_ACTIVITY||q.role()==Role.CANDIDATE_LEARNING||q.module().equals("SUBJECT_CONFIDENCE"))
                    option=catalog.options(k).stream().filter(o->target==null?o.unknown():Objects.equals(o.value(),target)).findFirst().orElseThrow();
                else option=catalog.options(k).stream().filter(Option::unknown).findFirst().orElse(catalog.options(k).get(0));
                answers.removeIf(a->a.key().equals(k));answers.add(new Answer(k,option.code(),true,true));
            }
        }
        void stageOne() {
            for(int i=0;i<5;i++) {StageOne plan=engine.stageOne(answers,history);if(plan.plan().toIssue().isEmpty())return;answer(plan.plan().toIssue());}
            throw new AssertionError("Stage one did not settle");
        }
        FixedCandidates freeze(){return engine.freezeCandidates(answers,history);}
    }
    @Test void loadsCurrentCatalogAndStartsWith33CoreQuestionsWithoutPrematureConfirmations() {
        assertThat(catalog.questions()).hasSize(187);assertThat(catalog.variants()).hasSize(32);
        StageOne initial=engine.stageOne(List.of(),List.of());
        assertThat(initial.route().route()).isNull();assertThat(initial.plan().toIssue()).hasSize(33);
        assertThat(initial.plan().active()).noneMatch(k->catalog.question(k.questionId()).module().equals("INTEREST_CONFIRM"));
    }
    @Test void groupAverageUsesAvailableTopThreeIncludingSportWithTwoAndExactThirdTie() {
        CandidateEngine e=candidateEngine(List.of(simple("H","HUM","INT_HUM"),simple("S","SOC","INT_SOC"),simple("E","EDU","INT_EDU"),
                simple("P1","SPORT","INT_SPORT"),simple("P2","SPORT","INT_PERFORM")));
        List<Response> scores=new ArrayList<>(List.of(metric("h","INT_HUM",100),metric("s","INT_SOC",75),metric("p1","INT_SPORT",50),metric("p2","INT_PERFORM",50)));
        for(int i=0;i<5;i++)scores.add(metric("e"+i,"INT_EDU",i<4?75:50)); // 70, exactly five below second.
        FixedCandidates result=e.freeze(scores);
        assertThat(result.groupCodes()).containsExactly("HUM","SOC","EDU");
        assertThat(result.rankedGroups().stream().filter(g->g.code().equals("SPORT")).findFirst().orElseThrow().score()).isEqualTo(50);
        scores.add(metric("e5","INT_EDU",50)); // below the tie boundary.
        assertThat(e.freeze(scores).groupCodes()).containsExactly("HUM","SOC");
    }
    @Test void topicOnlyFixingIgnoresActivityAndLearningAndAllowsEmptyPositivePool() {
        Run run=new Run(Route.BROAD);run.stageOne();FixedCandidates before=run.freeze();
        List<Response> changed=new ArrayList<>(catalog.scoringResponses(run.answers));
        changed.add(new Response("high-learning",Role.CANDIDATE_LEARNING,"LEARN_INF",100,true,true));
        assertThat(engine.candidates().freeze(changed)).isEqualTo(before);
        List<Response> negative=new ArrayList<>();for(String metric:catalog.scoring().calculator().aggregate(List.of()).interest().keySet())negative.add(metric(metric,metric,25));
        assertThat(engine.candidates().freeze(negative).majorCodes()).isEmpty();
    }
    @Test void stableRepresentativesKeepTwoPerGroupAndSevenTotalWithoutAddingGroups() {
        Run run=new Run(Route.BROAD);run.stageOne();FixedCandidates fixed=run.freeze();Display display=engine.candidates().rerank(fixed,catalog.scoringResponses(run.answers));
        assertThat(display.firstScreen()).hasSizeLessThanOrEqualTo(7);
        assertThat(display.candidates()).allMatch(s->fixed.groupCodes().contains(s.group()));
        for(String group:fixed.groupCodes()) {
            long count=display.candidates().stream().filter(s->s.group().equals(group)).count();
            assertThat(display.firstScreen().stream().filter(s->s.group().equals(group)).count()).isBetween(Math.min(2,count),Math.min(3,count));
        }
    }
    @Test void rejectsUnknownOrOutOfGroupFixedMajor() {
        assertThatThrownBy(()->engine.candidates().rerank(new FixedCandidates(List.of(),List.of("HUM"),List.of("missing")),List.of())).isInstanceOf(IllegalArgumentException.class);
        String code=catalog.scoring().majors().stream().filter(m->!m.group().equals("HUM")).findFirst().orElseThrow().majorCode();
        assertThatThrownBy(()->engine.candidates().validate(new FixedCandidates(List.of(),List.of("HUM"),List.of(code)))).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @EnumSource(Route.class)
    void consistentCoreSelectsEveryRouteAndProducesBoundedStageTwo(Route route) {
        Run run=new Run(route);run.stageOne();StageOne one=engine.stageOne(run.answers,run.history);
        assertThat(one.route().route()).isEqualTo(route);assertThat(one.plan().active()).hasSizeLessThanOrEqualTo(42);
        Plan two=engine.stageTwo(run.freeze(),run.answers,run.history,Presentation.none());
        assertCaps(two);run.answer(two.toIssue());
        Plan resumed=engine.stageTwo(run.freeze(),run.answers,run.history,Presentation.none());
        assertThat(run.history).doesNotHaveDuplicates();assertThat(resumed.toIssue()).hasSizeLessThanOrEqualTo(24-two.toIssue().size());
    }
    @Test void unsureAndConflictingStateRulesUseConfirmationPriorityAndNoInventedMeaning() {
        Map<String,String> state=new HashMap<>(Map.of("STATE-01","STATE_UNSURE","STATE-03","STATE_UNSURE","STATE-06","STATE_UNSURE"));
        assertThat(engine.route(state)).isEqualTo(new RouteDecision(Route.BROAD,"STATE_UNKNOWN",false));
        state.put("STATE-01","STATE_BROAD");state.put("STATE-03","STATE_COMPARE");state.put("STATE-06","STATE_REVIEW");
        assertThat(engine.route(state).confirmationRequired()).isTrue();assertThat(engine.route(state).route()).isNull();
        state.put("STATE-02","STATE_COMPARE_CONFIRM");state.put("STATE-04","STATE_BROAD_CONFIRM");state.put("STATE-05","STATE_REVIEW_CONFIRM");
        assertThat(engine.route(state).route()).isEqualTo(Route.BROAD);
        state.put("STATE-04","STATE_BROAD_REJECT");assertThat(engine.route(state).route()).isEqualTo(Route.COMPARE);
        state.put("STATE-02","STATE_COMPARE_REJECT");state.put("STATE-05","STATE_REVIEW_REJECT");assertThat(engine.route(state).reason()).isEqualTo("STATE_INCONSISTENT");
        state.put("STATE-06","STATE_COMPARE");assertThat(engine.route(state).reason()).isEqualTo("CORE_MAJORITY");
        state.put("STATE-06","typo");assertThatThrownBy(()->engine.route(state)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sportsAndPerformanceReserveFourInsideNineActivitySlots() {
        Run run=new Run(Route.BROAD);run.targets.put("INT_SPORT",100);run.targets.put("INT_PERFORM",100);run.targets.put("INT_HUM",75);run.stageOne();
        Plan two=engine.stageTwo(run.freeze(),run.answers,run.history,Presentation.none());
        assertThat(two.toIssue()).filteredOn(k->catalog.question(k.questionId()).module().equals("ACTIVITY_CONDITIONAL")).hasSize(4);
        assertCaps(two);
    }
    @Test void learningUsesActualLinkedSetIncludingEducationPerformingAndInfNotGroupAlias() {
        Run run=new Run(Route.COMPARE);run.stageOne();
        List<String> codes=catalog.scoring().majors().stream().filter(m->m.group().equals("EDU")&&m.learningSets().contains("LEARN_PERFORM")||m.learningSets().contains("LEARN_INF"))
                .map(MajorProfile::majorCode).limit(2).toList();
        for(String set:List.of("LEARN_PERFORM","LEARN_INF","LEARN_EDU")) {
            MajorProfile major=catalog.scoring().majors().stream().filter(m->m.learningSets().contains(set)).findFirst().orElseThrow();
            Plan two=engine.stageTwo(fixed(major.majorCode()),run.answers,run.history,Presentation.none());
            assertThat(two.toIssue()).filteredOn(k->catalog.question(k.questionId()).module().equals("LEARNING_CONTENT"))
                    .hasSize(set.equals("LEARN_EDU")?3:4).allMatch(k->catalog.question(k.questionId()).metric().equals(set));
        }
        assertThat(codes).isNotEmpty();
    }
    @Test void comparisonSameMembershipSkipAndDistinctMembershipRequireExactVariant() {
        Run run=new Run(Route.COMPARE);run.stageOne();
        List<MajorScore> same=engine.candidates().rerank(fixed("MAJOR_ROW_3","MAJOR_ROW_9"),catalog.scoringResponses(run.answers)).candidates();
        assertThat(engine.comparisons(same)).isEmpty();
        String a=null,b=null;
        outer:for(MajorProfile x:catalog.scoring().majors())for(MajorProfile y:catalog.scoring().majors())for(String set:catalog.comparison(x.majorCode()).sets())
            if(catalog.comparison(y.majorCode()).sets().contains(set)&&!catalog.comparison(x.majorCode()).memberships().get(set).equals(catalog.comparison(y.majorCode()).memberships().get(set))) {a=x.majorCode();b=y.majorCode();break outer;}
        assertThat(a).isNotNull();
        List<Key> comparisons=engine.comparisons(engine.candidates().rerank(fixed(a,b),catalog.scoringResponses(run.answers)).candidates());
        assertThat(comparisons).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        comparisons.forEach(k->{assertThat(catalog.variants().get(k.comparisonSet()).questions()).contains(k.questionId());assertThat(catalog.options(k)).anyMatch(Option::unknown);});
        Key chosen=comparisons.get(0);
        assertThatThrownBy(()->catalog.option(new Answer(new Key(chosen.questionId(),chosen.questionVersion(),chosen.comparisonSet(),"wrong"),"x",true,true))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void previouslyExposedExcludedQuestionsNeverRefundBudgetOrReissue() {
        Run run=new Run(Route.BROAD);run.stageOne();FixedCandidates fixed=run.freeze();
        Plan first=engine.stageTwo(fixed,run.answers,run.history,new Presentation(new HashSet<>(fixed.majorCodes()),new HashSet<>(fixed.majorCodes())));
        run.history.addAll(first.toIssue());assertThat(first.toIssue()).hasSize(24);
        Plan changed=engine.stageTwo(fixed,run.answers,run.history,Presentation.none());
        assertThat(changed.toIssue()).isEmpty();assertThat(changed.active()).allMatch(run.history::contains);
    }
    @Test void noSupportedCandidatesDoNotFabricateStageTwoQuestions() {
        Run run=new Run(Route.BROAD);catalog.scoring().calculator().aggregate(List.of()).interest().keySet().forEach(m->run.targets.put(m,25));run.stageOne();
        assertThat(run.freeze().majorCodes()).isEmpty();assertThat(engine.stageTwo(run.freeze(),run.answers,run.history,Presentation.none()).active()).isEmpty();
    }
    @Test void exhaustedComparisonBlockDoesNotReservePhantomSlotsForNewVariants() {
        Run run=new Run(Route.COMPARE);run.stageOne();FixedCandidates fixed=run.freeze();
        List<Key> original=engine.comparisons(engine.candidates().rerank(fixed,catalog.scoringResponses(run.answers)).candidates());
        assertThat(original).hasSize(3);
        String other=catalog.variants().keySet().stream().filter(s->original.stream().noneMatch(k->k.comparisonSet().equals(s))).findFirst().orElseThrow();
        for(String id:catalog.variants().get(other).questions())run.history.add(catalog.key(catalog.question(id),other));
        Plan plan=engine.stageTwo(fixed,run.answers,run.history,Presentation.none());
        assertThat(plan.toIssue()).noneMatch(k->!k.comparisonSet().isEmpty());
        assertThat(plan.toIssue()).hasSize(21);
    }
    @Test void earlyExitRequiresStrictGapConfirmedEvidenceAndModuleBoundary() {
        List<MajorProfile> singles=catalog.scoring().majors().stream().filter(m->m.structure()==InterestStructure.SINGLE).toList();
        MajorProfile a=singles.get(0),b=singles.stream().filter(m->!m.group().equals(a.group())&&!m.primary().equals(a.primary())
                &&Collections.disjoint(catalog.comparison(a.majorCode()).sets(),catalog.comparison(m.majorCode()).sets())).findFirst().orElseThrow();
        Run run=new Run(Route.BROAD);catalog.scoring().calculator().aggregate(List.of()).interest().keySet().forEach(m->run.targets.put(m,0));
        run.targets.put(a.primary(),100);run.targets.put(b.primary(),75);run.stageOne();
        FixedCandidates exact=new FixedCandidates(List.of(new GroupScore(a.group(),87),new GroupScore(b.group(),75)),List.of(a.group(),b.group()),List.of(a.majorCode(),b.majorCode()));
        assertThat(engine.canExitAtModuleBoundary(exact,run.answers,run.history,Presentation.none(),true)).isFalse();
        FixedCandidates over=new FixedCandidates(List.of(new GroupScore(a.group(),87.01),new GroupScore(b.group(),75)),exact.groupCodes(),exact.majorCodes());
        assertThat(engine.canExitAtModuleBoundary(over,run.answers,run.history,Presentation.none(),true)).isTrue();
        assertThat(engine.canExitAtModuleBoundary(over,run.answers,run.history,Presentation.none(),false)).isFalse();
        List<Answer> sparse=run.answers.stream().filter(x->!catalog.question(x.key().questionId()).module().equals("INTEREST_CONFIRM")).toList();
        assertThat(engine.canExitAtModuleBoundary(over,sparse,run.history,Presentation.none(),true)).isFalse();
    }
    @Test void learningActivationCannotBeSilentlyReinterpretedAsAnExplorationGroup()throws Exception {
        String altered=csv("questions.csv").replace("TOP_OR_TIE_GROUP:INF","TOP_OR_TIE_GROUP:ENG");
        assertThatThrownBy(()->new BranchCatalog(json("ruleset.json"),json("major-mapping.json"),json("semantic-dictionary.json"),
                altered,csv("options.csv"),csv("option-variants.csv"),csv("comparison-skip-pairs.csv"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.semantics(key("LEARN-INF-01")).learningSetReference()).isEqualTo("LEARN_INF");
        assertThat(catalog.semantics(key("LEARN-INF-01")).legacyActivation()).isEqualTo("TOP_OR_TIE_GROUP:INF");
        assertThat(catalog.semantics(key("OPEN-04")).canonicalResultUsage()).contains("고정 후보");
    }
    @Test void comparisonOmittedBySpentBudgetDoesNotAuthorizeEarlyExit() {
        MajorProfile a=null,b=null;
        outer:for(MajorProfile x:catalog.scoring().majors())for(MajorProfile y:catalog.scoring().majors())
            if(!x.group().equals(y.group()))for(String set:catalog.comparison(x.majorCode()).sets())
                if(catalog.comparison(y.majorCode()).sets().contains(set)&&!catalog.comparison(x.majorCode()).memberships().get(set).equals(catalog.comparison(y.majorCode()).memberships().get(set))) {a=x;b=y;break outer;}
        assertThat(a).isNotNull();
        Run run=new Run(Route.BROAD);catalog.scoring().calculator().aggregate(List.of()).interest().keySet().forEach(m->run.targets.put(m,0));
        for(String metric:List.of(a.primary(),a.secondary(),b.primary(),b.secondary()))run.targets.put(metric,100);
        run.stageOne();
        FixedCandidates fixed=new FixedCandidates(List.of(new GroupScore(a.group(),100),new GroupScore(b.group(),75)),List.of(a.group(),b.group()),List.of(a.majorCode(),b.majorCode()));
        for(String module:List.of("ACTIVITY_EXTEND","LEARNING_CONTENT","OPENNESS")) {
            int max=module.equals("ACTIVITY_EXTEND")?9:module.equals("LEARNING_CONTENT")?12:3;
            catalog.questions().stream().filter(q->q.module().equals(module)).limit(max).forEach(q->run.history.add(key(q.id())));
        }
        assertThat(engine.stageTwo(fixed,run.answers,run.history,Presentation.none()).active()).noneMatch(k->!k.comparisonSet().isEmpty());
        assertThat(engine.comparisons(engine.candidates().rerank(fixed,catalog.scoringResponses(run.answers)).candidates())).isNotEmpty();
        assertThat(engine.canExitAtModuleBoundary(fixed,run.answers,run.history,Presentation.none(),true)).isFalse();
    }
    @Test void learningRoundRobinKeepsFrozenCandidateOrderAndEducationThreeQuestionLimit() {
        Run run=new Run(Route.COMPARE);run.stageOne();
        Plan plan=engine.stageTwo(fixed("MAJOR_ROW_2","MAJOR_ROW_93","MAJOR_ROW_133"),run.answers,run.history,Presentation.none());
        List<String> ids=plan.active().stream().filter(k->catalog.question(k.questionId()).module().equals("LEARNING_CONTENT")).map(Key::questionId).toList();
        assertThat(ids).startsWith("LEARN-HUM-01","LEARN-EDU-01","LEARN-INF-01");
        assertThat(ids).filteredOn(id->id.startsWith("LEARN-EDU")).hasSize(3);
    }
    @Test void conflictPathReaches42Plus24Plus11WithoutFillingUnusedSeventyEighthSlot() {
        Run run=new Run(Route.BROAD);run.targets.put("CONF_MAT",0);run.targets.put("CONF_SCI",0);run.stageOne();
        for(var e:Map.of("STATE-01","STATE_BROAD","STATE-03","STATE_COMPARE","STATE-06","STATE_REVIEW").entrySet()) {
            Key key=key(e.getKey());String option=catalog.options(key).stream().filter(o->o.meaning().equals(e.getValue())).findFirst().orElseThrow().code();
            run.answers.removeIf(a->a.key().equals(key));run.answers.add(new Answer(key,option,true,true));
        }
        run.stageOne();assertThat(run.history).hasSize(42);
        FixedCandidates fixed=run.freeze();Plan two=engine.stageTwo(fixed,run.answers,run.history,Presentation.none());run.answer(two.toIssue());
        assertThat(run.history).hasSize(66);
        Plan three=engine.stageThree(fixed,run.answers,run.history,Map.of(fixed.majorCodes().get(0),Set.of("CAUTION_MATH","CAUTION_SCIENCE","CAUTION_FACE_TO_FACE","CAUTION_CREATIVE_WORK","CAUTION_LONG_PREP")));
        assertThat(three.toIssue()).hasSize(11);run.answer(three.toIssue());assertThat(run.history).hasSize(77).doesNotHaveDuplicates();
    }
    @Test void cautionGateIsExplicitAndNegativeBurdensAreSeparatedWithAtMostTwoSets() {
        Run run=new Run(Route.BROAD);run.targets.put("CONF_MAT",0);run.targets.put("CONF_SCI",0);run.stageOne();FixedCandidates fixed=run.freeze();
        Plan none=engine.stageThree(fixed,run.answers,run.history,Map.of());
        assertThat(none.active()).noneMatch(k->catalog.question(k.questionId()).module().equals("CAUTION_CHECK"));
        Plan three=engine.stageThree(fixed,run.answers,run.history,Map.of(fixed.majorCodes().get(0),Set.of("CAUTION_MATH","CAUTION_SCIENCE","CAUTION_FACE_TO_FACE","CAUTION_LONG_PREP")));
        assertThat(three.active()).hasSizeLessThanOrEqualTo(11);
        assertThat(three.active().stream().filter(k->catalog.question(k.questionId()).module().equals("CAUTION_CHECK")).map(k->catalog.question(k.questionId()).metric()).distinct().count()).isLessThanOrEqualTo(2);
        for(int i=1;i<three.active().size();i++)assertThat(List.of(catalog.question(three.active().get(i-1).questionId()).module(),catalog.question(three.active().get(i).questionId()).module()))
                .isNotEqualTo(List.of("SUBJECT_BURDEN","SUBJECT_BURDEN"));
    }
    @Test void adapterRejectsUnissuedOrForeignOptionAndIgnoresOldOrExcludedRevision() {
        Key q=key("INT-HUM-01");String option=catalog.options(q).stream().filter(o->Objects.equals(o.value(),75)).findFirst().orElseThrow().code();
        Answer answer=new Answer(q,option,true,true);
        assertThatThrownBy(()->engine.stageOne(List.of(answer),List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->catalog.scoringResponses(List.of(new Answer(q,"not-an-option",true,true)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.scoringResponses(List.of(answer,new Answer(q,"old",false,true),new Answer(q,"old",true,false)))).hasSize(1);
        assertThatThrownBy(()->catalog.scoringResponses(List.of(answer,answer))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->engine.stageTwo(fixed("MAJOR_ROW_2"),List.of(),List.of(),Presentation.none())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->engine.freezeCandidates(List.of(),List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->engine.stageOne(List.of(),List.of(q,q))).isInstanceOf(IllegalArgumentException.class);
        List<Key> tooMany=catalog.questions().stream().filter(x->x.stage().equals("STAGE_2")&&!x.module().equals("MAJOR_COMPARISON")).limit(25).map(x->key(x.id())).toList();
        assertThatThrownBy(()->engine.stageOne(List.of(),tooMany)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void open04NeverChangesFixedCandidatesAndNoPhaseTwoOnlyModulesLeak() {
        Run run=new Run(Route.BROAD);run.stageOne();FixedCandidates fixed=run.freeze();
        Plan two=engine.stageTwo(fixed,run.answers,run.history,new Presentation(Set.of(),new HashSet<>(fixed.majorCodes())));
        Key open=two.active().stream().filter(k->k.questionId().equals("OPEN-04")).findFirst().orElseThrow();
        List<MajorScore> before=engine.candidates().rerank(fixed,catalog.scoringResponses(run.answers)).candidates();
        run.history.add(open);String code=catalog.options(open).stream().filter(o->o.meaning().equals("OPEN_MULTIPLE")).findFirst().orElseThrow().code();
        run.answers.add(new Answer(open,code,true,true));
        assertThat(engine.candidates().rerank(fixed,catalog.scoringResponses(run.answers)).candidates()).isEqualTo(before);
        assertThat(two.active()).noneMatch(k->Set.of("VALUE","DECISION_STYLE").contains(catalog.question(k.questionId()).module()));
    }
    @Test void all376RecipesRunPureIssuedPlannerWithinCapsReproduciblyWithoutChangingFixtureStatus()throws Exception {
        JsonNode fixtures=JSON.readTree(Files.readString(Path.of("datasets/exploration-validation/reachability-profiles.json")));
        assertThat(fixtures.path("runtime_status").asText()).isEqualTo("NOT_EXECUTED");int count=0;
        for(JsonNode recipe:fixtures.path("profiles")) {
            Run run=new Run(Route.BROAD);
            run.maxInterestNumeric=recipe.path("interest_response_count_target").intValue();
            for(String kind:List.of("interest","activity","learning"))recipe.path(kind+"_targets").properties().forEach(e->run.targets.put(e.getKey(),e.getValue().isNull()?null:e.getValue().intValue()));
            run.stageOne();FixedCandidates fixed=run.freeze();Plan two=engine.stageTwo(fixed,run.answers,run.history,Presentation.none());assertCaps(two);
            if(run.maxInterestNumeric==1)assertThat(catalog.scoring().calculator().aggregate(catalog.scoringResponses(run.answers)).interest().values()).allMatch(a->a.validCount()<=1);
            List<Answer> reversed=new ArrayList<>(run.answers);Collections.reverse(reversed);
            assertThat(engine.stageTwo(fixed,reversed,run.history,Presentation.none())).isEqualTo(two);
            run.answer(two.toIssue());Display result=engine.candidates().rerank(fixed,catalog.scoringResponses(run.answers));
            assertThat(result.firstScreen()).hasSizeLessThanOrEqualTo(7);assertThat(fixed.groupCodes()).hasSizeLessThanOrEqualTo(3);
            assertThat(run.history).hasSizeLessThanOrEqualTo(66);count++;
        }
        assertThat(count).isEqualTo(376);
    }
    private static void assertCaps(Plan plan) {
        assertThat(plan.active()).doesNotHaveDuplicates().hasSizeLessThanOrEqualTo(24);
        assertThat(plan.active().stream().filter(k->Set.of("ACTIVITY_EXTEND","ACTIVITY_CONDITIONAL").contains(catalog.question(k.questionId()).module())).count()).isLessThanOrEqualTo(9);
        assertThat(plan.active().stream().filter(k->catalog.question(k.questionId()).module().equals("LEARNING_CONTENT")).count()).isLessThanOrEqualTo(12);
        assertThat(plan.active().stream().filter(k->!k.comparisonSet().isEmpty()).count()).isLessThanOrEqualTo(3);
    }
}
