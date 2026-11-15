package com.jinroon.jobe.domain.exploration.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import com.jinroon.jobe.domain.exploration.scoring.ScoringCatalog;
import static com.jinroon.jobe.domain.exploration.scoring.ExplorationScoreCalculator.*;

/** Adapter for validated pinned snapshots. No IO, persistence, approvals, or legacy code guessing. */
public final class BranchCatalog {
    public record Key(String questionId,String questionVersion,String comparisonSet,String variantVersion) {
        public String identity(){return String.join("|",questionId,questionVersion,comparisonSet,variantVersion);}
    }
    public record Question(String id,String version,String stage,String module,String order,String metric,Role role,String activation) {}
    public record Option(String code,String meaning,Integer value,boolean unknown) {}
    public record Variant(String code,String version,List<String> questions,Set<String> directions) {
        public Variant {questions=List.copyOf(questions);directions=Set.copyOf(directions);}
    }
    public record ComparisonProfile(List<String> sets,Map<String,Set<String>> memberships) {
        public ComparisonProfile {sets=List.copyOf(sets);memberships=Map.copyOf(memberships);}
    }
    public record Answer(Key key,String optionCode,boolean current,boolean effective) {}
    public record QuestionSemantics(String legacyActivation,String learningSetReference,String canonicalResultUsage,String semanticVersion) {}
    private final ScoringCatalog scoring;
    private final JsonNode rules,semantic;
    private final Map<String,Question> questions=new TreeMap<>();
    private final Map<String,Variant> variants=new TreeMap<>();
    private final Map<String,Map<String,Option>> options=new TreeMap<>();
    private final Map<String,ComparisonProfile> comparisons=new TreeMap<>();
    private final Set<String> skipPairs=new HashSet<>();

    public BranchCatalog(JsonNode rules,JsonNode mapping,JsonNode semantic,String questionCsv,String optionCsv,String variantCsv,String skipCsv) {
        scoring=ScoringCatalog.fromSnapshots(rules,mapping,semantic);this.rules=rules.deepCopy();this.semantic=semantic.deepCopy();
        for(Map<String,String> row:CsvReader.read(questionCsv)) {
            Question q=new Question(row.get("question_id"),row.get("question_version"),row.get("stage"),row.get("module_code"),
                    row.get("order_group"),row.get("metric_code"),Role.valueOf(row.get("scoring_role")),row.get("activation_condition"));
            check(semantic.path("module_roles").path(q.module()).asText().equals(q.role().name()),"Module/role mismatch");
            validateActivation(q);
            check(questions.put(q.id(),q)==null,"Duplicate question");
        }
        for(Map<String,String> row:CsvReader.read(optionCsv)) {
            Question q=question(row.get("question_id"));String set=row.get("comparison_set");
            check(q.metric().equals(row.get("metric_code")),"Option metric mismatch");
            Integer value=row.get("numeric_value").isBlank()?null:Integer.valueOf(row.get("numeric_value"));
            boolean unknown=Boolean.parseBoolean(row.get("is_unknown"))||Boolean.parseBoolean(row.get("is_no_experience"));
            check(!unknown||value==null,"Unknown/no-experience must not be numeric");
            Option option=new Option(row.get("option_code"),row.get("meaning_code"),value,unknown);
            check(options.computeIfAbsent(q.id()+"|"+set,k->new TreeMap<>()).put(option.code(),option)==null,"Duplicate option");
        }
        for(Map<String,String> row:CsvReader.read(variantCsv)) {
            String code=row.get("comparison_set");List<String> family=List.of(row.get("question_family").split("\\|"));
            Set<String> directions=null;
            for(String id:family) {
                check(question(id).module().equals("MAJOR_COMPARISON"),"Variant family mismatch");
                Map<String,Option> choices=options.get(id+"|"+code);check(choices!=null,"Missing variant options");
                check(choices.values().stream().anyMatch(o->o.unknown()&&o.meaning().equals("UNKNOWN")),"UNSURE missing");
                Set<String> current=new TreeSet<>();choices.values().stream().filter(o->!o.unknown()).forEach(o->current.add(o.meaning()));
                if(directions==null)directions=current;else check(directions.equals(current),"Variant directions differ across family");
            }
            check(variants.put(code,new Variant(code,row.get("option_variant_version"),family,directions))==null,"Duplicate variant");
        }
        for(JsonNode row:mapping.path("majors")) {
            List<String> sets=strings(row.path("comparison_sets"));Set<String> targets=new HashSet<>(strings(row.path("comparison_targets")));
            Map<String,Set<String>> memberships=new TreeMap<>();
            for(String set:sets) {
                Variant variant=variants.get(set);check(variant!=null,"Unknown comparison set");
                Set<String> intersection=new TreeSet<>(variant.directions());intersection.retainAll(targets);
                check(!intersection.isEmpty(),"No direction membership");memberships.put(set,Set.copyOf(intersection));
            }
            comparisons.put(row.path("major_code").asText(),new ComparisonProfile(sets,memberships));
        }
        for(Map<String,String> row:CsvReader.read(skipCsv)) {
            String set=row.get("comparison_set"),a=row.get("major_a"),b=row.get("major_b");
            check(comparison(a).memberships().containsKey(set)&&comparison(b).memberships().containsKey(set),"Invalid skip pair");
            check(comparison(a).memberships().get(set).equals(comparison(b).memberships().get(set)),"Skip pair has distinct memberships");
            skipPairs.add(pair(set,a,b));
        }
    }
    private void validateActivation(Question q) {
        String a=q.activation();String expected=switch(q.module()) {
            case "INTEREST_CONFIRM"->"TOP_OR_TIE_OR_LOW_EVIDENCE:"+q.metric();
            case "ACTIVITY_CONDITIONAL"->"TOP_OR_TIE:"+(q.metric().equals("ACT_PHY")?"INT_SPORT":"INT_PERFORM");
            case "LEARNING_CONTENT"->semantic.path("legacy_learning_activation").has(a)?a:"INVALID";
            default->null;
        };
        if(expected!=null)check(expected.equals(a),"Invalid contextual activation");
        else if(!semantic.path("activation_plain").has(a)) {
            String[] parts=a.split(":",2);check(parts.length==2,"Unknown activation");
            JsonNode domain=semantic.path("activation_parameter_domains").path(parts[0]);
            check(domain.isArray()&&strings(domain).contains(parts[1]),"Unknown activation parameter");
        }
        if(q.module().equals("LEARNING_CONTENT"))check(semantic.path("legacy_learning_activation").path(a).asText().equals(q.metric()),"Learning activation is not its learning set");
    }
    public ScoringCatalog scoring(){return scoring;}
    public JsonNode rules(){return rules.deepCopy();}
    public List<Question> questions(){return List.copyOf(questions.values());}
    public Question question(String id){Question q=questions.get(id);check(q!=null,"Unknown question: "+id);return q;}
    public Map<String,Variant> variants(){return Collections.unmodifiableMap(variants);}
    public ComparisonProfile comparison(String code){ComparisonProfile p=comparisons.get(code);check(p!=null,"Unknown major");return p;}
    public boolean skip(String set,String a,String b){return skipPairs.contains(pair(set,a,b));}
    public Key key(Question q,String set) {
        if(q.module().equals("MAJOR_COMPARISON")) {
            Variant v=variants.get(set);check(v!=null&&v.questions().contains(q.id()),"Wrong comparison family");
            return new Key(q.id(),q.version(),set,v.version());
        }
        check(set.isEmpty(),"Non-comparison question with comparison set");return new Key(q.id(),q.version(),"","");
    }
    public void validate(Key key){check(key.equals(key(question(key.questionId()),key.comparisonSet())),"Question/variant version mismatch");}
    public Option option(Answer answer) {
        validate(answer.key());Map<String,Option> choices=options.get(answer.key().questionId()+"|"+answer.key().comparisonSet());
        Option result=choices==null?null:choices.get(answer.optionCode());check(result!=null,"Option not in pinned question/variant");return result;
    }
    public List<Answer> active(List<Answer> answers) {
        Set<Key> seen=new HashSet<>();List<Answer> result=new ArrayList<>();
        for(Answer a:answers)if(a.current()&&a.effective()) {option(a);check(seen.add(a.key()),"Duplicate current effective answer");result.add(a);}
        return List.copyOf(result);
    }
    public List<Response> scoringResponses(List<Answer> answers) {
        return active(answers).stream().map(a->{Question q=question(a.key().questionId());Option o=option(a);
            return new Response(a.key().identity(),q.role(),q.metric(),o.unknown()?null:o.value(),true,true);}).toList();
    }
    public List<Option> options(Key key){validate(key);return List.copyOf(options.get(key.questionId()+"|"+key.comparisonSet()).values());}
    public QuestionSemantics semantics(Key key) {
        validate(key);Question q=question(key.questionId());
        return new QuestionSemantics(q.activation(),q.module().equals("LEARNING_CONTENT")?q.metric():"",
                q.id().equals("OPEN-04")?semantic.path("open04").path("canonical_result_usage").asText():"",scoring.semanticVersion());
    }
    private static String pair(String set,String a,String b){return set+"|"+(a.compareTo(b)<0?a+"|"+b:b+"|"+a);}
    private static List<String> strings(JsonNode node){check(node.isArray(),"Expected array");List<String> out=new ArrayList<>();node.forEach(x->out.add(x.asText()));return List.copyOf(out);}
    private static void check(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}
