package com.jinroon.jobe.domain.exploration.branching;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import com.jinroon.jobe.domain.exploration.catalog.BranchCatalog;
import static com.jinroon.jobe.domain.exploration.catalog.BranchCatalog.*;
import static com.jinroon.jobe.domain.exploration.scoring.ExplorationScoreCalculator.*;
import static com.jinroon.jobe.domain.exploration.branching.CandidateEngine.*;

/** Deterministic pure pre-result planner. API layer owns transactions, issued snapshots and revisions. */
public final class QuestionBranchEngine {
    public enum Route { BROAD, COMPARE, REVIEW }
    public record RouteDecision(Route route,String reason,boolean confirmationRequired) {}
    public record Plan(List<Key> active,List<Key> toIssue,List<Key> omitted,boolean budgetLimited) {
        public Plan {active=List.copyOf(active);toIssue=List.copyOf(toIssue);omitted=List.copyOf(omitted);}
    }
    public record StageOne(RouteDecision route,Plan plan,int validScreenCount,boolean lowDifferentiation,
                           List<String> topAxes,List<String> tieAxes) {
        public StageOne {topAxes=List.copyOf(topAxes);tieAxes=List.copyOf(tieAxes);}
    }
    public record Presentation(Set<String> unexpected,Set<String> unfamiliar) {
        public Presentation {unexpected=Set.copyOf(unexpected);unfamiliar=Set.copyOf(unfamiliar);}
        public static Presentation none(){return new Presentation(Set.of(),Set.of());}
    }
    private record View(List<Answer> answers,List<Response> responses,Axes axes,Map<Key,Answer> byKey) {}
    private record Priority(boolean reserved,int comparisonRank,double contribution,int evidence,double gap) {}
    private final BranchCatalog catalog;
    private final JsonNode rules;
    private final CandidateEngine candidates;
    private final Map<String,MajorProfile> profiles=new TreeMap<>();
    public QuestionBranchEngine(BranchCatalog catalog) {
        this.catalog=Objects.requireNonNull(catalog);rules=catalog.rules();
        candidates=new CandidateEngine(catalog.scoring(),threshold("third_group_tie_gap_lte"),threshold("major_tie_gap_lte"));
        catalog.scoring().majors().forEach(m->profiles.put(m.majorCode(),m));
    }
    public CandidateEngine candidates(){return candidates;}
    public FixedCandidates freezeCandidates(List<Answer> answers,List<Key> history) {
        View view=view(answers,history);StageOne one=stageOne(answers,history);
        check(one.route().route()!=null&&view.byKey().keySet().containsAll(one.plan().active()),"Stage one responses incomplete");
        return candidates.freeze(view.responses());
    }
    public StageOne stageOne(List<Answer> answers,List<Key> history) {
        View view=view(answers,history);Map<String,String> state=new TreeMap<>();
        view.answers().stream().filter(a->catalog.question(a.key().questionId()).module().equals("STATE"))
                .forEach(a->state.put(a.key().questionId(),catalog.option(a).meaning()));
        RouteDecision route=route(state);
        List<String> ranked=view.axes().interest().entrySet().stream().filter(e->e.getValue().score()!=null)
                .sorted(Comparator.<Map.Entry<String,AxisScore>,Double>comparing(e->e.getValue().score(),Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey)).map(Map.Entry::getKey).toList();
        List<String> top=ranked.stream().limit(3).toList();List<String> ties=new ArrayList<>();
        if(ranked.size()>=3)for(String metric:ranked.subList(3,ranked.size()))
            if(view.axes().interest().get(ranked.get(2)).score()-view.axes().interest().get(metric).score()<=threshold("interest_tie_gap_lte"))ties.add(metric);
        LinkedHashSet<String> confirmations=new LinkedHashSet<>(top);confirmations.addAll(ties);
        view.axes().interest().entrySet().stream().filter(e->e.getValue().validCount()<2).map(Map.Entry::getKey).sorted().forEach(confirmations::add);
        List<String> confirm=confirmations.stream().limit(cap("interest_confirmation")).toList();
        List<Key> desired=new ArrayList<>();
        for(String id:List.of("STATE-01","STATE-03","STATE-06"))desired.add(key(id));
        if(route.confirmationRequired())for(String id:List.of("STATE-02","STATE-04","STATE-05"))desired.add(key(id));
        desired.addAll(module("INTEREST_SCREEN").stream().map(q->key(q.id())).toList());
        boolean screeningSubmitted=module("INTEREST_SCREEN").stream().allMatch(q->view.byKey().containsKey(key(q.id())));
        if(screeningSubmitted)for(String metric:confirm)module("INTEREST_CONFIRM").stream().filter(q->q.metric().equals(metric)).forEach(q->desired.add(key(q.id())));
        desired.addAll(module("ACTIVITY_BASE").stream().map(q->key(q.id())).toList());
        desired.addAll(module("SUBJECT_CONFIDENCE").stream().map(q->key(q.id())).toList());
        int screens=(int)view.answers().stream().filter(a->catalog.question(a.key().questionId()).module().equals("INTEREST_SCREEN")&&catalog.option(a).value()!=null).count();
        List<Double> valid=view.axes().interest().values().stream().map(AxisScore::score).filter(Objects::nonNull).toList();
        boolean low=!valid.isEmpty()&&Collections.max(valid)-Collections.min(valid)<threshold("low_differentiation_range_lt");
        return new StageOne(route,allocate("STAGE_1",desired,history),screens,low,top,ties);
    }
    public RouteDecision route(Map<String,String> meanings) {
        for(var e:meanings.entrySet()) {
            check(Set.of("STATE-01","STATE-02","STATE-03","STATE-04","STATE-05","STATE-06").contains(e.getKey()),"Non-state question in route");
            Set<String> allowed=switch(e.getKey()) {
                case "STATE-01","STATE-03","STATE-06"->Set.of("STATE_BROAD","STATE_COMPARE","STATE_REVIEW","STATE_UNSURE");
                case "STATE-02"->Set.of("STATE_COMPARE_CONFIRM","STATE_COMPARE_REJECT","STATE_UNSURE");
                case "STATE-04"->Set.of("STATE_BROAD_CONFIRM","STATE_BROAD_REJECT","STATE_UNSURE");
                default->Set.of("STATE_REVIEW_CONFIRM","STATE_REVIEW_REJECT","STATE_UNSURE");
            };check(allowed.contains(e.getValue()),"Invalid state meaning");
        }
        List<String> core=List.of("STATE-01","STATE-03","STATE-06");
        if(!meanings.keySet().containsAll(core))return new RouteDecision(null,"AWAIT_CORE",false);
        List<String> values=core.stream().map(meanings::get).toList();
        if(values.stream().allMatch("STATE_UNSURE"::equals))return new RouteDecision(Route.BROAD,"STATE_UNKNOWN",false);
        if(new HashSet<>(values).size()==1)return new RouteDecision(Route.valueOf(values.get(0).substring(6)),"CORE_CONSISTENT",false);
        if(!meanings.keySet().containsAll(List.of("STATE-02","STATE-04","STATE-05")))return new RouteDecision(null,"AWAIT_CONFIRMATION",true);
        for(Route r:Route.values())if(Collections.frequency(values,"STATE_"+r)>=2)return new RouteDecision(r,"CORE_MAJORITY",true);
        for(Route r:Route.values())if(meanings.containsValue("STATE_"+r+"_CONFIRM"))return new RouteDecision(r,"CONFIRMED_STATE",true);
        return new RouteDecision(Route.BROAD,"STATE_INCONSISTENT",true);
    }
    public Plan stageTwo(FixedCandidates fixed,List<Answer> answers,List<Key> history,Presentation presentation) {
        candidates.validate(fixed);View view=view(answers,history);StageOne one=stageOne(answers,history);
        check(one.route().route()!=null,"State routing is incomplete");
        check(view.byKey().keySet().containsAll(one.plan().active()),"Stage one responses incomplete");
        List<MajorScore> ranked=candidates.rerank(fixed,view.responses()).candidates();
        List<MajorProfile> relevant=ranked.stream().map(s->profiles.get(s.majorCode())).toList();
        Set<String> high=new HashSet<>(one.topAxes());high.addAll(one.tieAxes());
        List<Key> activity=new ArrayList<>();
        for(Question q:module("ACTIVITY_CONDITIONAL"))if(high.contains(q.activation().substring(q.activation().indexOf(':')+1)))activity.add(key(q.id()));
        boolean info=ranked.stream().anyMatch(s->s.activity()==null)||ranked.stream().anyMatch(a->ranked.stream()
                .anyMatch(b->!a.majorCode().equals(b.majorCode())&&a.group().equals(b.group())&&Math.abs(a.total()-b.total())<=threshold("major_tie_gap_lte")));
        if(info)for(Question q:module("ACTIVITY_EXTEND"))if(relevant.stream().anyMatch(m->m.activityAxes().contains(q.metric())))activity.add(key(q.id()));
        if(fixed.majorCodes().isEmpty())activity.clear();
        activity.sort(bestFirst(view,ranked));
        activity=new ArrayList<>(activity.subList(0,Math.min(cap("activity_block"),activity.size())));
        Set<String> currentCodes=new HashSet<>();ranked.forEach(s->currentCodes.add(s.majorCode()));
        List<Key> learning=learning(fixed.majorCodes().stream().filter(currentCodes::contains).map(profiles::get).toList(),history);
        List<Key> comparison=comparisons(ranked);
        List<Key> openness=new ArrayList<>();
        boolean unexpected=ranked.stream().anyMatch(m->presentation.unexpected().contains(m.majorCode()));
        boolean unfamiliar=ranked.stream().anyMatch(m->presentation.unfamiliar().contains(m.majorCode()));
        for(Question q:module("OPENNESS")) {
            boolean active=switch(q.activation()) {
                case "STATE_BROAD_OR_UNEXPECTED_RESULT"->one.route().route()==Route.BROAD||unexpected;
                case "UNEXPECTED_CANDIDATE_EXISTS"->unexpected;
                case "UNFAMILIAR_CANDIDATE_EXISTS"->unfamiliar;
                default->throw new IllegalArgumentException("Unsupported openness condition");
            };if(active&&!ranked.isEmpty())openness.add(key(q.id()));
        }
        List<Key> environment=one.lowDifferentiation()&&!ranked.isEmpty()?module("ACTIVITY_ENVIRONMENT_STYLE").stream().map(q->key(q.id())).toList():List.of();
        Map<String,List<Key>> blocks=Map.of("ACTIVITY",activity,"LEARNING",learning,"COMPARISON",comparison,"OPENNESS",openness,"ENVIRONMENT",environment);
        List<Key> ordered=new ArrayList<>(),trimmed=new ArrayList<>();
        // Earlier modules survive over-budget trimming. Within a module drop lowest contribution first.
        int available=Math.max(0,Math.min(cap("stage2")-countStage(history,"STAGE_2"),cap("pre_result")-history.size()));
        for(JsonNode block:rules.path("stage2").path("path_priority").path(one.route().route().name())) {
            List<Key> queue=new ArrayList<>(blocks.get(block.asText()));
            List<Key> old=queue.stream().filter(history::contains).toList();
            List<Key> fresh=new ArrayList<>();
            List<Key> previouslySelected=new ArrayList<>(ordered.stream().filter(k->!history.contains(k)).toList());
            for(Key key:queue)if(!history.contains(key)&&withinBlockCap(key,history,previouslySelected)) {
                fresh.add(key);previouslySelected.add(key);
            }
            if(fresh.size()>available) {fresh.sort(bestFirst(view,ranked));fresh=new ArrayList<>(fresh.subList(0,available));}
            Set<Key> selected=new HashSet<>(old);selected.addAll(fresh);
            queue.stream().filter(k->!selected.contains(k)).forEach(trimmed::add);
            queue.stream().filter(selected::contains).forEach(ordered::add);available-=fresh.size();
        }
        Plan plan=allocate("STAGE_2",ordered,history);trimmed.addAll(plan.omitted());
        return new Plan(plan.active(),plan.toIssue(),trimmed,!trimmed.isEmpty());
    }
    private List<Key> learning(List<MajorProfile> candidates,List<Key> history) {
        LinkedHashSet<String> sets=new LinkedHashSet<>();candidates.forEach(m->sets.addAll(m.learningSets()));
        Map<String,List<Key>> queues=new LinkedHashMap<>();
        for(String set:sets) {
            int max=set.equals("LEARN_EDU")?3:4;
            List<Key> all=module("LEARNING_CONTENT").stream().filter(q->q.metric().equals(set)).map(q->key(q.id())).toList();
            List<Key> old=all.stream().filter(history::contains).toList();
            List<Key> queue=new ArrayList<>(old);
            all.stream().filter(k->!history.contains(k)).limit(Math.max(0,max-old.size())).forEach(queue::add);queues.put(set,queue);
        }
        return roundRobin(queues.values(),cap("learning"));
    }
    public List<Key> comparisons(List<MajorScore> candidates) {
        Map<String,Integer> priorities=new TreeMap<>();
        for(int i=0;i<candidates.size();i++)for(int j=i+1;j<candidates.size();j++) {
            String a=candidates.get(i).majorCode(),b=candidates.get(j).majorCode();
            ComparisonProfile x=catalog.comparison(a),y=catalog.comparison(b);
            for(String set:x.sets())if(y.sets().contains(set)&&!catalog.skip(set,a,b)
                    &&!x.memberships().get(set).equals(y.memberships().get(set))) {
                int rank=(x.sets().indexOf(set)==0?0:1)+(y.sets().indexOf(set)==0?0:1);
                priorities.merge(set,rank,Math::min);
            }
        }
        List<List<Key>> queues=priorities.keySet().stream().sorted(Comparator.comparingInt((String s)->priorities.get(s)).thenComparing(s->s))
                .map(set->catalog.variants().get(set).questions().stream().map(id->catalog.key(catalog.question(id),set)).toList()).toList();
        return roundRobin(queues,cap("comparison_total"));
    }
    /** eligibleTags must come from the server's D05 scope/review gate, never from client claims or proposed tags. */
    public Plan stageThree(FixedCandidates fixed,List<Answer> answers,List<Key> history,Map<String,Set<String>> eligibleTags) {
        candidates.validate(fixed);View view=view(answers,history);List<MajorScore> ranked=candidates.rerank(fixed,view.responses()).candidates();
        Set<String> knownTags=new HashSet<>();module("CAUTION_CHECK").forEach(q->knownTags.add(q.metric()));module("SUBJECT_BURDEN").forEach(q->knownTags.add(q.metric()));
        eligibleTags.forEach((code,tags)->{check(profiles.containsKey(code),"Unknown caution major");check(knownTags.containsAll(tags),"Unknown eligible caution tag");});
        Map<String,Integer> tagOrder=new HashMap<>();
        for(int i=0;i<ranked.size();i++)for(String tag:eligibleTags.getOrDefault(ranked.get(i).majorCode(),Set.of()))tagOrder.merge(tag,i,Math::min);
        Map<String,Double> confidence=new HashMap<>();view.answers().stream().filter(a->catalog.question(a.key().questionId()).module().equals("SUBJECT_CONFIDENCE")&&catalog.option(a).value()!=null)
                .forEach(a->confidence.put(catalog.question(a.key().questionId()).metric(),catalog.option(a).value().doubleValue()));
        List<String> tags=tagOrder.keySet().stream().filter(t->!Set.of("CAUTION_MATH","CAUTION_SCIENCE").contains(t)||lowConfidence(t,confidence))
                .filter(t->module("CAUTION_CHECK").stream().anyMatch(q->q.metric().equals(t)))
                .sorted(Comparator.comparing((String t)->!lowConfidence(t,confidence)).thenComparingInt(tagOrder::get).thenComparing(t->t)).limit(cap("caution_sets")).toList();
        List<Key> ordered=new ArrayList<>();for(String tag:tags)module("CAUTION_CHECK").stream().filter(q->q.metric().equals(tag)).limit(2).forEach(q->ordered.add(key(q.id())));
        List<Key> burdens=module("SUBJECT_BURDEN").stream().filter(q->tagOrder.containsKey(q.metric())).limit(4).map(q->key(q.id())).toList();
        List<Key> separators=module("EXPLORATION_CONSTRAINT").stream().filter(q->!q.metric().equals("CONSTRAINT_LONG_PREP")||tagOrder.containsKey("CAUTION_LONG_PREP")).limit(3).map(q->key(q.id())).toList();
        int bi=0;for(Key separator:separators){if(bi<burdens.size())ordered.add(burdens.get(bi++));ordered.add(separator);}
        if(bi<burdens.size()&&!ordered.isEmpty()&&!catalog.question(ordered.get(ordered.size()-1).questionId()).module().equals("SUBJECT_BURDEN"))ordered.add(burdens.get(bi));
        return allocate("STAGE_3",ordered,history);
    }
    private boolean lowConfidence(String tag,Map<String,Double> values) {
        String metric=switch(tag){case "CAUTION_MATH"->"CONF_MAT";case "CAUTION_SCIENCE"->"CONF_SCI";default->"";};
        return values.containsKey(metric)&&values.get(metric)<=threshold("low_confidence_lte");
    }
    public boolean canExitAtModuleBoundary(FixedCandidates fixed,List<Answer> answers,List<Key> history,Presentation presentation,boolean moduleBoundary) {
        if(!moduleBoundary||fixed.groupCodes().size()<2)return false;
        View view=view(answers,history);StageOne one=stageOne(answers,history);
        if(one.route().route()==null||one.validScreenCount()<threshold("screen_valid_min")||!view.byKey().keySet().containsAll(one.plan().active()))return false;
        double first=fixed.rankedGroups().stream().filter(g->g.code().equals(fixed.groupCodes().get(0))).findFirst().orElseThrow().score();
        double second=fixed.rankedGroups().stream().filter(g->g.code().equals(fixed.groupCodes().get(1))).findFirst().orElseThrow().score();
        if(first-second<=threshold("early_exit_group_gap_gt"))return false;
        List<MajorScore> shown=candidates.rerank(fixed,view.responses()).firstScreen();if(shown.isEmpty())return false;
        for(MajorScore score:shown)for(String metric:score.usedInterestAxes()) {
            AxisScore axis=view.axes().interest().get(metric);if(axis.validCount()<2||axis.inconsistent())return false;
        }
        // Budget trimming is not evidence that an eligible comparison has been answered.
        return comparisons(candidates.rerank(fixed,view.responses()).candidates()).stream().allMatch(view.byKey()::containsKey);
    }
    private View view(List<Answer> answers,List<Key> history) {
        validateHistory(history);
        List<Answer> active=catalog.active(answers);Map<Key,Answer> map=new HashMap<>();
        for(Answer a:active){check(history.contains(a.key()),"Answer to unissued instance");map.put(a.key(),a);}
        List<Response> resolved=catalog.scoringResponses(active);
        return new View(active,resolved,catalog.scoring().calculator().aggregate(resolved),map);
    }
    private Plan allocate(String stage,List<Key> desired,List<Key> history) {
        validateHistory(history);
        int remaining=Math.max(0,Math.min(cap(stage.toLowerCase().replace("_",""))-countStage(history,stage),cap("pre_result")-history.size()));
        List<Key> active=new ArrayList<>(),fresh=new ArrayList<>(),omitted=new ArrayList<>();
        for(Key key:new LinkedHashSet<>(desired)) {
            catalog.validate(key);check(catalog.question(key.questionId()).stage().equals(stage),"Stage mismatch");
            if(history.contains(key)){active.add(key);continue;}
            boolean room=remaining>0&&withinBlockCap(key,history,fresh);
            if(room){active.add(key);fresh.add(key);remaining--;}else omitted.add(key);
        }
        // A removed burden must not leave two negative burden questions adjacent.
        if(stage.equals("STAGE_3"))for(int i=1;i<active.size();i++)if(isBurden(active.get(i))&&isBurden(active.get(i-1))) {
            Key removed=active.remove(i--);fresh.remove(removed);omitted.add(removed);
        }
        return new Plan(active,fresh,omitted,!omitted.isEmpty());
    }
    private void validateHistory(List<Key> history) {
        check(new HashSet<>(history).size()==history.size(),"Duplicate issued instance");history.forEach(catalog::validate);
        check(history.size()<=cap("pre_result"),"Pre-result history exceeds budget");
        for(String stage:List.of("STAGE_1","STAGE_2","STAGE_3"))check(countStage(history,stage)<=cap(stage.toLowerCase().replace("_","")),"Stage history exceeds budget");
        Map<String,Integer> learning=new HashMap<>();Set<String> cautionSets=new HashSet<>();
        int activity=0,comparison=0,confirm=0;
        for(Key key:history) {
            Question q=catalog.question(key.questionId());check(Set.of("STAGE_1","STAGE_2","STAGE_3").contains(q.stage()),"Post-result history is not a pre-result input");
            if(q.stage().equals("STAGE_2")&&block(q.module()).equals("ACTIVITY"))activity++;
            if(q.module().equals("MAJOR_COMPARISON"))comparison++;
            if(q.module().equals("INTEREST_CONFIRM"))confirm++;
            if(q.module().equals("LEARNING_CONTENT"))learning.merge(q.metric(),1,Integer::sum);
            if(q.module().equals("CAUTION_CHECK"))cautionSets.add(q.metric());
        }
        check(activity<=cap("activity_block")&&comparison<=cap("comparison_total")&&confirm<=cap("interest_confirmation"),"Block history exceeds budget");
        check(learning.values().stream().mapToInt(Integer::intValue).sum()<=cap("learning")&&cautionSets.size()<=cap("caution_sets"),"Learning/caution history exceeds budget");
        learning.forEach((set,count)->check(count<=(set.equals("LEARN_EDU")?3:4),"Learning set history exceeds budget"));
    }
    private boolean withinBlockCap(Key key,List<Key> history,List<Key> fresh) {
        Question q=catalog.question(key.questionId());List<Key> used=new ArrayList<>(history);used.addAll(fresh);
        String block=block(q.module());long count=used.stream().filter(k->block(catalog.question(k.questionId()).module()).equals(block)).count();
        if(block.equals("ACTIVITY")&&q.stage().equals("STAGE_2"))count=used.stream().filter(k->catalog.question(k.questionId()).stage().equals("STAGE_2")&&block(catalog.question(k.questionId()).module()).equals("ACTIVITY")).count();
        int max=switch(block){case "ACTIVITY"->q.stage().equals("STAGE_2")?cap("activity_block"):9;case "LEARNING"->cap("learning");case "COMPARISON"->cap("comparison_total");case "CONFIRM"->cap("interest_confirmation");default->Integer.MAX_VALUE;};
        if(count>=max)return false;
        if(block.equals("CAUTION_CHECK")) {
            Set<String> tags=new HashSet<>();used.stream().filter(k->catalog.question(k.questionId()).module().equals("CAUTION_CHECK"))
                    .forEach(k->tags.add(catalog.question(k.questionId()).metric()));
            if(!tags.contains(q.metric())&&tags.size()>=cap("caution_sets"))return false;
            return used.stream().filter(k->catalog.question(k.questionId()).module().equals("CAUTION_CHECK")&&catalog.question(k.questionId()).metric().equals(q.metric())).count()<2;
        }
        if(block.equals("LEARNING"))return used.stream().filter(k->catalog.question(k.questionId()).metric().equals(q.metric())).count()<(q.metric().equals("LEARN_EDU")?3:4);
        return true;
    }
    private Comparator<Key> bestFirst(View view,List<MajorScore> majors) {
        Map<Key,Priority> cache=new HashMap<>();
        java.util.function.Function<Key,Priority> get=k->cache.computeIfAbsent(k,x->priority(x,view,majors));
        return Comparator.comparing((Key k)->!get.apply(k).reserved())
                .thenComparingInt(k->get.apply(k).comparisonRank())
                .thenComparing((Key k)->-get.apply(k).contribution())
                .thenComparingInt(k->get.apply(k).evidence()).thenComparingDouble(k->get.apply(k).gap())
                .thenComparing(k->catalog.question(k.questionId()).order()).thenComparing(Key::questionId).thenComparing(Key::comparisonSet);
    }
    private Priority priority(Key key,View view,List<MajorScore> majors) {
        Question q=catalog.question(key.questionId());Map<String,AxisScore> axes=q.role()==Role.CANDIDATE_ACTIVITY?view.axes().activity():q.role()==Role.CANDIDATE_LEARNING?view.axes().learning():view.axes().interest();
        int evidence=axes.containsKey(q.metric())?axes.get(q.metric()).validCount():0;
        List<Double> loads=majors.stream().map(s->load(profiles.get(s.majorCode()),q.metric())).toList();
        double contribution=loads.isEmpty()?0:Collections.max(loads)-Collections.min(loads),gap=Double.POSITIVE_INFINITY;
        for(int i=0;i<loads.size();i++)for(int j=i+1;j<loads.size();j++)if(!loads.get(i).equals(loads.get(j)))gap=Math.min(gap,Math.abs(majors.get(i).total()-majors.get(j).total()));
        int comparisonRank=3;
        if(!key.comparisonSet().isEmpty())for(int i=0;i<majors.size();i++)for(int j=i+1;j<majors.size();j++) {
            ComparisonProfile a=catalog.comparison(majors.get(i).majorCode()),b=catalog.comparison(majors.get(j).majorCode());
            String set=key.comparisonSet();
            if(a.sets().contains(set)&&b.sets().contains(set)&&!a.memberships().get(set).equals(b.memberships().get(set)))
                comparisonRank=Math.min(comparisonRank,(a.sets().indexOf(set)==0?0:1)+(b.sets().indexOf(set)==0?0:1));
        }
        return new Priority(q.module().equals("ACTIVITY_CONDITIONAL"),comparisonRank,contribution,evidence,gap);
    }
    private double load(MajorProfile major,String metric) {
        int index=major.activityAxes().indexOf(metric);
        return index>=0?rules.path("weights").path("activity").get(index).doubleValue():major.learningSets().contains(metric)?1:0;
    }
    private static List<Key> roundRobin(Collection<List<Key>> queues,int max) {
        List<Key> result=new ArrayList<>();int index=0;boolean added=true;
        while(added&&result.size()<max){added=false;for(List<Key> q:queues)if(index<q.size()&&result.size()<max){result.add(q.get(index));added=true;}index++;}
        return result;
    }
    private Key key(String id){return catalog.key(catalog.question(id),"");}
    private List<Question> module(String module){return catalog.questions().stream().filter(q->q.module().equals(module)).sorted(Comparator.comparing(Question::order).thenComparing(Question::id)).toList();}
    private int countStage(List<Key> history,String stage){return (int)history.stream().filter(k->catalog.question(k.questionId()).stage().equals(stage)).count();}
    private static String block(String module){return switch(module){case "ACTIVITY_BASE","ACTIVITY_EXTEND","ACTIVITY_CONDITIONAL"->"ACTIVITY";case "LEARNING_CONTENT"->"LEARNING";case "MAJOR_COMPARISON"->"COMPARISON";case "INTEREST_CONFIRM"->"CONFIRM";default->module;};}
    private boolean isBurden(Key key){return catalog.question(key.questionId()).module().equals("SUBJECT_BURDEN");}
    private int cap(String key){check(rules.path("caps").path(key).isIntegralNumber(),"Missing cap: "+key);return rules.path("caps").path(key).intValue();}
    private double threshold(String key){check(rules.path("thresholds").path(key).isNumber(),"Missing threshold: "+key);return rules.path("thresholds").path(key).doubleValue();}
    private static void check(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}
