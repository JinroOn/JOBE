package com.jinroon.jobe.domain.exploration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Wire validation only. Declared evidence metadata is never a database approval or permission to generate. */
@Component
@RequiredArgsConstructor
public class ExplorationAiContract {
    public static final String VERSION="EXPLORATION_AI_CONTRACT_V1_0";
    public static final String CONVERSATION_VERSION="EXPLORATION_AI_CONTRACT_V1_1";
    public static final String PLAN12_VERSION="EXPLORATION_AI_CONTRACT_V1_2";
    public static String promptVersion(JsonNode request){return PLAN12_VERSION.equals(request.path("contractVersion").asText())?"EXPLORATION_PLAN12_V1_2":CONVERSATION_VERSION.equals(request.path("contractVersion").asText())?"EXPLORATION_CONVERSATION_V1_0":"EXPLORATION_MODEL_V1_0";}
    private final ExplorationJson json;
    private static final Set<String> TOPICS=Set.of("READINESS-INFO-01","READINESS-CMP-01","READINESS-SELF-01","READINESS-ACT-02","READINESS-ACT-01","READINESS-CON-01","READINESS-INFO-02","READINESS-SELF-02","ACTIVITY-REPORT-01","ACTIVITY-PROJECT-01","ACTIVITY-READ-01","ACTIVITY-EXPERIMENT-01","ACTIVITY-PRESENT-01","ACTIVITY-CONTENT-01");
    public static final class Invalid extends RuntimeException {public Invalid(){super("Invalid exploration AI contract");}}
    public String inputHash(JsonNode request){var body=json.object();for(String key:List.of("sourceContextHash","purpose","input","evidence"))body.set(key,request.path(key).deepCopy());return json.hex(body);}
    public void request(JsonNode r){
        fields(r,"contractVersion","requestId","sourceContextHash","inputHash","purpose","mode","testOnly","input","evidence");
        boolean conversation=CONVERSATION_VERSION.equals(r.path("contractVersion").asText());
        boolean plan12=PLAN12_VERSION.equals(r.path("contractVersion").asText());
        require(conversation||plan12||VERSION.equals(r.path("contractVersion").asText()));pattern(r.path("requestId"),"[A-Za-z0-9_-]{1,80}");pattern(r.path("sourceContextHash"),"[a-f0-9]{64}");pattern(r.path("inputHash"),"[a-f0-9]{64}");
        require(Set.of("CONSULTATION","PLAN").contains(r.path("purpose").asText()));require("DEV".equals(r.path("mode").asText()));truth(r.path("testOnly"));
        JsonNode input=r.path("input");
        if(conversation){require("CONSULTATION".equals(r.path("purpose").asText()));fields(input,"diagnosisStatus","candidateNames","targetName","uncertainty","preferences","collection","conversation");conversation(input.path("conversation"));}
        else if(plan12){require("PLAN".equals(r.path("purpose").asText()));fields(input,"diagnosisStatus","candidateNames","targetName","uncertainty","preferences","collection","planSpec");var spec=input.path("planSpec");fields(spec,"weeks","phaseLayout");integer(spec.path("weeks"),12,12);require("INFO_4_BASIC_4_ACTIVITY_4".equals(spec.path("phaseLayout").asText()));}
        else fields(input,"diagnosisStatus","candidateNames","targetName","uncertainty","preferences","collection");
        require(Set.of("READY","LOW_EVIDENCE","NO_SUPPORTED_CANDIDATE").contains(input.path("diagnosisStatus").asText()));strings(input.path("candidateNames"),0,8,100,true);
        JsonNode target=input.path("targetName");if(!target.isNull()){text(target,100);require(contains(input.path("candidateNames"),target.asText()));}
        if(r.path("purpose").asText().equals("PLAN"))require(!target.isNull()&&!input.path("diagnosisStatus").asText().equals("NO_SUPPORTED_CANDIDATE"));
        if(input.path("diagnosisStatus").asText().equals("NO_SUPPORTED_CANDIDATE"))require(input.path("candidateNames").isEmpty()&&target.isNull());
        else require(!input.path("candidateNames").isEmpty());
        JsonNode uncertainty=input.path("uncertainty");fields(uncertainty,"lowEvidence","interestTie","lowDifferentiation");for(JsonNode value:uncertainty)bool(value);
        require(uncertainty.path("lowEvidence").asBoolean()==input.path("diagnosisStatus").asText().equals("LOW_EVIDENCE"));
        JsonNode prefs=input.path("preferences");array(prefs,0,8);Set<String> topics=new HashSet<>();
        for(JsonNode pref:prefs){fields(pref,"topic","meaning","unknown");require(TOPICS.contains(pref.path("topic").asText())&&topics.add(pref.path("topic").asText()));pattern(pref.path("meaning"),"[A-Z][A-Z0-9_]{0,99}");bool(pref.path("unknown"));}
        JsonNode collection=input.path("collection");fields(collection,"initialized","applicableAnswerCount","budgetLimited","omittedQuestionCount");bool(collection.path("initialized"));bool(collection.path("budgetLimited"));integer(collection.path("applicableAnswerCount"),0,8);integer(collection.path("omittedQuestionCount"),0,8);
        require(collection.path("applicableAnswerCount").asInt()==prefs.size());if(!collection.path("initialized").asBoolean())require(prefs.isEmpty()&&!collection.path("budgetLimited").asBoolean()&&collection.path("omittedQuestionCount").asInt()==0);
        JsonNode evidence=r.path("evidence");fields(evidence,"approvedOnly","bindingStatus","datasetVersion","referenceKeys");truth(evidence.path("approvedOnly"));strings(evidence.path("referenceKeys"),0,10,80,true);for(JsonNode ref:evidence.path("referenceKeys"))pattern(ref,"[A-Za-z0-9_-]{1,80}");
        if(evidence.path("bindingStatus").asText().equals("NOT_BOUND"))require(evidence.path("datasetVersion").isNull()&&evidence.path("referenceKeys").isEmpty());
        else {require(evidence.path("bindingStatus").asText().equals("BOUND"));text(evidence.path("datasetVersion"),100);require(!evidence.path("referenceKeys").isEmpty());}
        require(inputHash(r).equals(r.path("inputHash").asText()));
    }
    public void response(JsonNode request,JsonNode response){
        request(request);fields(response,"contractVersion","requestId","inputHash","purpose","status","changesRecommendation","reasonCode","result");
        for(String field:List.of("contractVersion","requestId","inputHash","purpose"))require(response.path(field).isTextual()&&response.path(field).equals(request.path(field)));
        bool(response.path("changesRecommendation"));require(!response.path("changesRecommendation").asBoolean());
        String status=response.path("status").asText();
        if(status.equals("INSUFFICIENT_EVIDENCE")){require("APPROVED_EVIDENCE_UNAVAILABLE".equals(response.path("reasonCode").asText())&&response.path("result").isNull());return;}
        if(status.equals("DECLINED")){require(Set.of("OUT_OF_SCOPE","POLICY_RESTRICTION").contains(response.path("reasonCode").asText())&&response.path("result").isNull());return;}
        require(status.equals("SUCCEEDED")&&response.path("reasonCode").isNull()&&request.path("evidence").path("bindingStatus").asText().equals("BOUND"));
        JsonNode result=response.path("result");
        if(request.path("purpose").asText().equals("CONSULTATION")){fields(result,"summary","questions","referenceKeys");text(result.path("summary"),4000);strings(result.path("questions"),0,3,300,false);references(result.path("referenceKeys"),request);}
        else if(PLAN12_VERSION.equals(request.path("contractVersion").asText())){
            fields(result,"targetName","weeks");require(result.path("targetName").equals(request.path("input").path("targetName")));array(result.path("weeks"),12,12);
            for(int i=0;i<12;i++){var w=result.path("weeks").get(i);fields(w,"week","phase","title","tasks","expectedOutput","referenceKeys");integer(w.path("week"),i+1,i+1);require((i<4?"INFO":i<8?"BASIC_LEARNING":"SMALL_ACTIVITY").equals(w.path("phase").asText()));text(w.path("title"),100);strings(w.path("tasks"),1,3,300,false);text(w.path("expectedOutput"),300);references(w.path("referenceKeys"),request);}
        }
        else {fields(result,"targetName","overview","steps");require(result.path("targetName").equals(request.path("input").path("targetName")));text(result.path("overview"),2000);array(result.path("steps"),1,6);
            for(JsonNode step:result.path("steps")){fields(step,"title","description","referenceKeys");text(step.path("title"),200);text(step.path("description"),600);references(step.path("referenceKeys"),request);}}
    }
    public ObjectNode insufficient(JsonNode request){request(request);var out=json.object();for(String field:List.of("contractVersion","requestId","inputHash","purpose"))out.set(field,request.path(field).deepCopy());out.put("status","INSUFFICIENT_EVIDENCE");out.put("changesRecommendation",false);out.put("reasonCode","APPROVED_EVIDENCE_UNAVAILABLE");out.putNull("result");return out;}
    /** History is untrusted dialogue, never an approval or a new source reference. */
    public void conversation(JsonNode value){
        fields(value,"history","question");text(value.path("question"),2000);
        JsonNode history=value.path("history");array(history,0,6);require(history.size()%2==0);
        int size=value.path("question").asText().codePointCount(0,value.path("question").asText().length());
        for(int i=0;i<history.size();i++){
            JsonNode message=history.get(i);fields(message,"role","content");String role=i%2==0?"user":"assistant";
            require(role.equals(message.path("role").asText()));text(message.path("content"),role.equals("user")?2000:4000);
            String content=message.path("content").asText();size+=content.codePointCount(0,content.length());
        }
        require(size<=8000);
    }
    private void references(JsonNode refs,JsonNode request){strings(refs,1,10,80,true);for(JsonNode ref:refs)require(contains(request.path("evidence").path("referenceKeys"),ref.asText()));}
    private boolean contains(JsonNode values,String s){for(JsonNode value:values)if(value.asText().equals(s))return true;return false;}
    private void fields(JsonNode value,String... names){require(value!=null&&value.isObject()&&value.size()==names.length);Set<String> expected=Set.of(names);value.fieldNames().forEachRemaining(k->require(expected.contains(k)));}
    private void strings(JsonNode node,int min,int max,int chars,boolean unique){array(node,min,max);Set<String> seen=new HashSet<>();for(JsonNode item:node){text(item,chars);if(unique)require(seen.add(item.asText()));}}
    private void array(JsonNode node,int min,int max){require(node.isArray()&&node.size()>=min&&node.size()<=max);}
    private void integer(JsonNode value,int min,int max){require(value.isIntegralNumber()&&value.canConvertToInt()&&value.intValue()>=min&&value.intValue()<=max);}
    private void text(JsonNode value,int max){require(value.isTextual());String s=value.asText();require(!s.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0x85)&&s.codePointCount(0,s.length())<=max);for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isHighSurrogate(c)){require(i+1<s.length()&&Character.isLowSurrogate(s.charAt(i+1)));i++;}else require(!Character.isLowSurrogate(c));}}
    private void pattern(JsonNode value,String pattern){require(value.isTextual()&&value.asText().matches(pattern));}
    private void bool(JsonNode value){require(value.isBoolean());}
    private void truth(JsonNode value){bool(value);require(value.asBoolean());}
    private static void require(boolean ok){if(!ok)throw new Invalid();}
}
