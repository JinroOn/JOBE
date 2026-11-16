package com.jinroon.jobe.domain.exploration.sharing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Explicit public allowlist. Never wraps a result entity, owner DTO, answers, cautions, support or plans. */
@Component
@RequiredArgsConstructor
public class SharedResultProjector {
    public static final String LEGACY_CONTRACT="SHARED_EXPLORATION_SUMMARY_V1_0";
    public static final String CONTRACT="SHARED_EXPLORATION_SUMMARY_V1_1";
    private final ExplorationJson json;
    private final com.jinroon.jobe.domain.exploration.display.ExplorationResultDisplay display;
    public ObjectNode project(JsonNode result,String nickname){
        return project(result,nickname,CONTRACT);
    }
    public ObjectNode project(JsonNode result,String nickname,String contract){
        if(!java.util.Set.of(LEGACY_CONTRACT,CONTRACT).contains(contract))throw com.jinroon.jobe.domain.exploration.api.ExplorationApiException.integrity();
        var out=json.object();out.put("summaryVersion",contract);out.put("runMode","DEV");out.put("testOnly",true);
        if(nickname!=null)out.put("nickname",nickname);
        JsonNode presented=CONTRACT.equals(contract)?display.project(result):result;
        var groups=out.putArray("groups");for(JsonNode source:presented.path(CONTRACT.equals(contract)?"groups":"groupSummaries")){
            var group=groups.addObject();group.put("groupCode",source.path("groupCode").asText());
            if(CONTRACT.equals(contract)){group.set("name",source.path("name"));group.set("summary",source.path("summary"));}
        }
        var majors=out.putArray("majors");int count=0;
        for(JsonNode source:result.path("majorSummaries")){if(count++>=7)break;var major=majors.addObject();major.put("majorName",source.path("majorName").asText());major.put("groupCode",source.path("groupCode").asText());major.put("explanation","입력한 흥미와 활동·학습 응답을 바탕으로 살펴볼 탐색 후보입니다.");}
        var uncertainty=out.putObject("uncertainty");uncertainty.put("lowEvidence",result.path("status").asText().equals("LOW_EVIDENCE"));uncertainty.put("noSupportedCandidate",result.path("status").asText().equals("NO_SUPPORTED_CANDIDATE"));
        uncertainty.put("interestTie",result.path("flags").path("interestTie").asBoolean());uncertainty.put("lowDifferentiation",result.path("flags").path("lowDifferentiation").asBoolean());
        uncertainty.put("message","탐색을 위한 요약이며 능력·적성·성공 또는 합격 가능성을 판정하지 않습니다.");
        out.putArray("nextActivities");out.put("activityAvailability","NOT_INCLUDED");
        out.putArray("nextSteps").add("관심 있는 전공의 공식 학과 안내와 교육과정을 확인해 보세요.");return out;
    }
    /** Reject appended/private fields even if a corrupted stored summary has a newly calculated hash. */
    public boolean allowed(JsonNode value){
        if(!fields(value,java.util.Set.of("summaryVersion","runMode","testOnly","nickname","groups","majors","uncertainty","nextActivities","activityAvailability","nextSteps")))return false;
        String contract=value.path("summaryVersion").asText();
        if(!java.util.Set.of(LEGACY_CONTRACT,CONTRACT).contains(contract)||!"DEV".equals(value.path("runMode").asText())||!value.path("testOnly").asBoolean())return false;
        if(value.has("nickname")&&(!value.path("nickname").isTextual()||value.path("nickname").asText().length()>50))return false;
        if(!value.path("groups").isArray()||value.path("groups").size()>3||!value.path("majors").isArray()||value.path("majors").size()>7)return false;
        for(JsonNode row:value.path("groups")){
            if(!fields(row,CONTRACT.equals(contract)?java.util.Set.of("groupCode","name","summary"):java.util.Set.of("groupCode"))||!row.path("groupCode").isTextual())return false;
            if(CONTRACT.equals(contract)&&(!row.path("name").isTextual()||!row.path("summary").isTextual()))return false;
        }
        for(JsonNode row:value.path("majors"))if(!fields(row,java.util.Set.of("majorName","groupCode","explanation"))||!row.path("majorName").isTextual()||!row.path("groupCode").isTextual()||!row.path("explanation").isTextual())return false;
        return fields(value.path("uncertainty"),java.util.Set.of("lowEvidence","noSupportedCandidate","interestTie","lowDifferentiation","message"))&&value.path("nextActivities").isArray()&&value.path("nextActivities").isEmpty()&&"NOT_INCLUDED".equals(value.path("activityAvailability").asText());
    }
    private boolean fields(JsonNode node,java.util.Set<String> allowed){if(!node.isObject())return false;var keys=node.fieldNames();while(keys.hasNext())if(!allowed.contains(keys.next()))return false;return true;}
}
