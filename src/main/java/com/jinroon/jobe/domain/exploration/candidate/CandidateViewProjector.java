package com.jinroon.jobe.domain.exploration.candidate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.display.ExplorationResultDisplay;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import static com.jinroon.jobe.domain.exploration.candidate.CandidateViewDtos.*;

/** Pure versioned view over final sealed candidates. Never calculates scores or selects/reorders candidates. */
@Component
@RequiredArgsConstructor
public class CandidateViewProjector {
    public static final String VERSION = "RESULT_CANDIDATE_VIEW_V1_0";
    public static final String BASIS_POLICY = "RECOMMENDATION_BASIS_V1_0";
    private static final String MESSAGE = "현재 진단에서는 같은 추천 근거를 사용하는 전공들입니다. 관심 있는 학과의 공식 교육과정과 탐색 내용을 함께 확인해 보세요.";
    private final ExplorationJson json;
    private final ExplorationResultDisplay display;
    private record Scope(List<JsonNode> candidates, Set<String> first, List<String> groups) {}

    public ObjectNode freeze(JsonNode result, JsonNode bundle) {
        Scope scope = scope(result);
        ObjectNode out = json.object(); out.put("viewVersion", VERSION); out.put("basisPolicyVersion", BASIS_POLICY);
        JsonNode majors = bundle.path("mapping").path("majors");
        require(scope.candidates().isEmpty() || majors.isArray());
        out.put("catalogMajorCount", majors.isArray() ? majors.size() : 0);
        out.set("sourceVersions", result.path("versions").deepCopy());
        out.set("sourceHashes", result.path("sourceHashes").deepCopy());
        var profiles = out.putObject("basisInputs"); var cards = out.putObject("cards"); var labels = out.putObject("groupLabels");
        Map<String, JsonNode> byCode = new HashMap<>();
        if (majors.isArray()) for (JsonNode m : majors) require(byCode.put(text(m,"major_code"),m)==null);
        JsonNode wording = bundle.path("displayContent");
        for (JsonNode candidate : scope.candidates()) {
            String code = text(candidate,"majorCode"); JsonNode mapping = byCode.get(code); require(mapping != null);
            ObjectNode profile = json.object();
            for (var field : Map.of("group", "exploration_group", "structure", "interest_structure", "primary", "primary_interest_axes", "secondary", "secondary_interest_axes").entrySet()) {
                JsonNode value = mapping.path(field.getValue());
                if (field.getKey().equals("primary") || field.getKey().equals("secondary")) {
                    require(value.isArray() && value.size()==1 && value.get(0).isTextual()); value = value.get(0);
                }
                profile.set(field.getKey(), value.deepCopy());
            }
            profile.set("activityAxes", mapping.path("activity_axes").deepCopy());
            profile.set("learningSets", mapping.path("learning_content_sets").deepCopy());
            validateProfile(candidate,profile);
            profiles.set(code,profile);
            ObjectNode card = display.candidateDescription(candidate,wording); card.put("name",text(mapping,"major_name"));
            // An existing frozen first-screen description is authoritative for historical wording.
            for (JsonNode old : result.path("display").path("majors")) if (code.equals(old.path("majorCode").asText())) {
                card.put("name",text(old,"name"));
                if (old.path("reasons").isArray() && old.path("explanation").isTextual()) {
                    card.set("reasons",old.path("reasons").deepCopy()); card.set("explanation",old.path("explanation").deepCopy());
                    card.put("reasonAvailability","AVAILABLE"); card.putNull("reasonUnavailableCode");
                }
            }
            cards.set(code,card);
        }
        for (String group : scope.groups()) {
            JsonNode label = wording.path("groups").path(group).path("name");
            for (JsonNode old : result.path("display").path("groups"))
                if (group.equals(old.path("groupCode").asText()) && old.path("name").isTextual()) label = old.path("name");
            if (label.isTextual() && !label.asText().isBlank()) labels.set(group,label.deepCopy()); else labels.putNull(group);
        }
        out.set("basisGroups",json.tree(basisGroups(scope,profiles)));
        validate(result,out);
        return out;
    }

    public JsonNode metadata(JsonNode result,long resultId) {
        // Older minimal snapshots may have no complete candidate projection. Never infer a missing array as zero.
        if (!result.has("candidateView") && (!result.path("rankedCandidates").isArray()
                || !result.path("firstScreen").isArray() || !result.path("fixedCandidates").path("groupCodes").isArray())) return null;
        Scope s = result.has("candidateView") ? validate(result,result.path("candidateView")) : scope(result);
        List<GroupCount> groups = new ArrayList<>();
        for (String group : s.groups()) {
            List<JsonNode> members = s.candidates().stream().filter(c->group.equals(c.path("group").asText())).toList();
            int first = (int)members.stream().filter(c->s.first().contains(c.path("majorCode").asText())).count();
            groups.add(new GroupCount(group,members.size(),first,members.size()-first));
        }
        return json.tree(new Browsing(VERSION,"/api/exploration/v1/results/"+resultId+"/candidates",s.candidates().size(),s.first().size(),s.candidates().size()-s.first().size(),groups));
    }

    public JsonNode response(JsonNode result,JsonNode view,long resultId,String source) {
        require(Set.of("COMPLETION_SNAPSHOT","SEALED_RESULT_PROJECTION").contains(source));
        Scope s = validate(result,view);
        List<BasisGroup> basis = basisGroups(s,view.path("basisInputs"));
        Map<String,String> groupIds = new HashMap<>(); basis.forEach(g->g.majorCodes().forEach(code->groupIds.put(code,g.id())));
        List<Card> items = new ArrayList<>(); List<Group> groups = new ArrayList<>();
        for (JsonNode c : s.candidates()) {
            String code = text(c,"majorCode"); JsonNode card = view.path("cards").path(code);
            List<Reason> reasons = new ArrayList<>();
            for (JsonNode reason : card.path("reasons")) reasons.add(new Reason(text(reason,"kind"),text(reason,"reasonCode"),text(reason,"text")));
            items.add(new Card(code,text(card,"name"),text(c,"group"),s.first().contains(code),text(card,"evidenceLevel"),List.copyOf(reasons),
                    card.path("explanation").textValue(),text(card,"reasonAvailability"),card.path("reasonUnavailableCode").textValue(),groupIds.get(code)));
        }
        for (String group : s.groups()) {
            List<Card> members = items.stream().filter(c->group.equals(c.groupCode())).toList();
            String name = view.path("groupLabels").path(group).textValue();
            groups.add(new Group(group,name,name==null?"UNAVAILABLE":"AVAILABLE",members.size(),(int)members.stream().filter(c->!c.isFirstScreen()).count()));
        }
        require(result.path("testOnly").isBoolean());
        return json.tree(new View(VERSION,BASIS_POLICY,resultId,text(result,"resultContractVersion"),source,text(result,"status"),text(result,"runMode"),result.path("testOnly").asBoolean(),
                items.size(),s.first().size(),items.size()-s.first().size(),List.copyOf(groups),List.copyOf(items),basis));
    }

    private Scope validate(JsonNode result,JsonNode view) {
        exact(view,Set.of("viewVersion","basisPolicyVersion","catalogMajorCount","sourceVersions","sourceHashes","basisInputs","cards","groupLabels","basisGroups"));
        require(VERSION.equals(text(view,"viewVersion")) && BASIS_POLICY.equals(text(view,"basisPolicyVersion")));
        require(result.path("versions").isObject() && result.path("sourceHashes").isObject());
        require(json.write(result.path("versions")).equals(json.write(view.path("sourceVersions"))) && json.write(result.path("sourceHashes")).equals(json.write(view.path("sourceHashes"))));
        Scope s = scope(result);
        require(view.path("catalogMajorCount").isIntegralNumber() && view.path("catalogMajorCount").asInt()>=s.candidates().size() && view.path("catalogMajorCount").asInt()<=94);
        Set<String> codes = new HashSet<>(); s.candidates().forEach(c->codes.add(text(c,"majorCode")));
        exact(view.path("basisInputs"),codes); exact(view.path("cards"),codes); exact(view.path("groupLabels"),new HashSet<>(s.groups()));
        for (String group : s.groups()) require(view.path("groupLabels").path(group).isNull()
                || (view.path("groupLabels").path(group).isTextual() && !view.path("groupLabels").path(group).asText().isBlank()));
        for (JsonNode c : s.candidates()) {
            String code = text(c,"majorCode"); validateProfile(c,view.path("basisInputs").path(code));
            JsonNode card = view.path("cards").path(code);
            exact(card,Set.of("name","evidenceLevel","reasons","explanation","reasonAvailability","reasonUnavailableCode"));
            text(card,"name"); require(display.candidateEvidenceLevel(c).equals(text(card,"evidenceLevel")));
            require(card.path("reasons").isArray());
            if ("AVAILABLE".equals(text(card,"reasonAvailability"))) {
                require(card.path("reasons").size()==3 && card.path("reasonUnavailableCode").isNull());text(card,"explanation");
                int i=0;for(JsonNode r:card.path("reasons")){exact(r,Set.of("kind","reasonCode","text"));require(List.of("INTEREST","ACTIVITY","LEARNING").get(i++).equals(text(r,"kind")));text(r,"reasonCode");text(r,"text");}
            } else require("UNAVAILABLE".equals(card.path("reasonAvailability").asText()) && card.path("reasons").isEmpty() && card.path("explanation").isNull()
                    && "HISTORICAL_DISPLAY_CONTENT_UNAVAILABLE".equals(card.path("reasonUnavailableCode").asText()));
        }
        require(json.write(view.path("basisGroups")).equals(json.write(basisGroups(s,view.path("basisInputs")))));
        return s;
    }

    private List<BasisGroup> basisGroups(Scope s,JsonNode profiles) {
        Map<String,List<String>> same = new LinkedHashMap<>();
        for (JsonNode c : s.candidates()) {
            String code = text(c,"majorCode"); ObjectNode signature = json.object();
            signature.set("profile",profiles.path(code));
            for (String field : List.of("evidence","usedInterestAxes","usedActivityAxes","usedLearningSets","topic","activity","learning","total")) signature.set(field,c.path(field));
            same.computeIfAbsent(json.write(signature),k->new ArrayList<>()).add(code);
        }
        List<BasisGroup> groups = new ArrayList<>();
        for (List<String> members : same.values()) if (members.size()>1) groups.add(new BasisGroup("basis-"+(groups.size()+1),"SAME_RECOMMENDATION_BASIS",List.copyOf(members),MESSAGE));
        return List.copyOf(groups);
    }

    private Scope scope(JsonNode result) {
        require(result.path("rankedCandidates").isArray() && result.path("firstScreen").isArray());
        List<String> fixedGroups = strings(result.path("fixedCandidates").path("groupCodes")); require(fixedGroups.size()<=3);
        List<String> fixedCodes = strings(result.path("fixedCandidates").path("majorCodes"));
        List<JsonNode> candidates = new ArrayList<>(); Set<String> codes = new HashSet<>(), actualGroups = new HashSet<>();
        for (JsonNode c : result.path("rankedCandidates")) {
            String code = text(c,"majorCode"), group = text(c,"group");
            require(codes.add(code) && fixedCodes.contains(code) && fixedGroups.contains(group));
            require(c.path("positiveTopicEvidence").isBoolean() && c.path("positiveTopicEvidence").asBoolean());
            number(c.path("total"),false); number(c.path("topic"),false); number(c.path("activity"),true); number(c.path("learning"),true);
            for (String kind : List.of("interest","activity","learning")) {
                JsonNode axes = c.path("evidence").path(kind); require(axes.isObject());
                for (JsonNode axis : axes) {require(axis.path("validCount").isIntegralNumber() && axis.path("validCount").asInt()>=0 && axis.path("inconsistent").isBoolean());number(axis.path("score"),true);require((axis.path("validCount").asInt()==0)==axis.path("score").isNull());}
            }
            for (var pair : Map.of("usedInterestAxes","interest","usedActivityAxes","activity","usedLearningSets","learning").entrySet())
                for(String metric:strings(c.path(pair.getKey()))) require(c.path("evidence").path(pair.getValue()).has(metric) && !c.path("evidence").path(pair.getValue()).path(metric).path("score").isNull());
            require(!strings(c.path("usedInterestAxes")).isEmpty()); candidates.add(c); actualGroups.add(group);
        }
        require(candidates.size()<=94);
        Set<String> first = new HashSet<>();
        for (JsonNode c : result.path("firstScreen")) require(first.add(text(c,"majorCode")) && codes.contains(c.path("majorCode").asText()));
        require(first.size()<=7 && (candidates.isEmpty() ? first.isEmpty() : !first.isEmpty()));
        String status = text(result,"status"); require(Set.of("READY","LOW_EVIDENCE","NO_SUPPORTED_CANDIDATE").contains(status));
        require(candidates.isEmpty()==status.equals("NO_SUPPORTED_CANDIDATE"));
        return new Scope(List.copyOf(candidates),Set.copyOf(first),fixedGroups.stream().filter(actualGroups::contains).toList());
    }

    private void validateProfile(JsonNode candidate,JsonNode p) {
        exact(p,Set.of("group","structure","primary","secondary","activityAxes","learningSets"));
        require(text(candidate,"group").equals(text(p,"group")) && Set.of("SINGLE","PRIMARY","HYBRID").contains(text(p,"structure")));
        text(p,"primary");text(p,"secondary");require(strings(p.path("activityAxes")).size()==3 && strings(p.path("learningSets")).size()<=1);
        require(p.path("structure").asText().equals("SINGLE")==p.path("primary").asText().equals(p.path("secondary").asText()));
        require(candidate.path("evidence").path("interest").has(p.path("primary").asText()) && candidate.path("evidence").path("interest").has(p.path("secondary").asText()));
        for(String metric:strings(p.path("activityAxes")))require(candidate.path("evidence").path("activity").has(metric));
        for(String metric:strings(p.path("learningSets")))require(candidate.path("evidence").path("learning").has(metric));
        for (String metric : strings(candidate.path("usedInterestAxes"))) require(List.of(p.path("primary").asText(),p.path("secondary").asText()).contains(metric));
        require(strings(p.path("activityAxes")).containsAll(strings(candidate.path("usedActivityAxes"))) && strings(p.path("learningSets")).containsAll(strings(candidate.path("usedLearningSets"))));
        List<String> usedActivity=strings(candidate.path("usedActivityAxes"));
        require(strings(p.path("activityAxes")).stream().filter(usedActivity::contains).toList().equals(usedActivity));
    }
    private static List<String> strings(JsonNode n) {require(n.isArray());List<String> result=new ArrayList<>();for(JsonNode x:n){require(x.isTextual()&&!x.asText().isBlank()&&!result.contains(x.asText()));result.add(x.asText());}return List.copyOf(result);}
    private static String text(JsonNode n,String key){require(n.path(key).isTextual()&&!n.path(key).asText().isBlank());return n.path(key).asText();}
    private static void number(JsonNode n,boolean nullable){require((nullable&&n.isNull()) || (n.isNumber()&&Double.isFinite(n.asDouble())&&n.asDouble()>=0&&n.asDouble()<=100));}
    private static void exact(JsonNode n,Set<String> fields){require(n.isObject()&&n.size()==fields.size());n.fieldNames().forEachRemaining(k->require(fields.contains(k)));}
    private static void require(boolean value){if(!value)throw ExplorationApiException.integrity();}
}
