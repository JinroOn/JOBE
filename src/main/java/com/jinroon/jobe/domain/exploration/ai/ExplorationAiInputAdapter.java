package com.jinroon.jobe.domain.exploration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** No network, provider credentials, RAG search or generation authorization. */
@Component
@RequiredArgsConstructor
public class ExplorationAiInputAdapter {
    private final ExplorationJson json;
    private final ExplorationAiContract contract;
    public JsonNode build(JsonNode context,String requestId){
        if(!ResultContextService.CONTRACT.equals(context.path("contractVersion").asText())||!"EXPLORATION_V1".equals(context.path("resultType").asText())||!"SEMANTIC_CONTRACT_V1_0".equals(context.path("source").path("versions").path("semanticContract").asText())||!context.path("runMode").asText().equals("DEV")||!context.path("testOnly").asBoolean())throw new ExplorationAiContract.Invalid();
        var r=json.object();r.put("contractVersion",ExplorationAiContract.VERSION);r.put("requestId",requestId);r.put("sourceContextHash",json.hex(context));r.put("purpose",context.path("contextType").asText());r.put("mode","DEV");r.put("testOnly",true);
        var input=r.putObject("input");input.put("diagnosisStatus",context.path("result").path("status").asText());
        var candidates=input.putArray("candidateNames");Set<String> names=new LinkedHashSet<>();context.path("result").path("majorSummaries").forEach(m->names.add(m.path("majorName").asText()));
        String target=context.path("targetMajor").path("major_name").textValue();if(target!=null)names.add(target);names.forEach(candidates::add);input.put("targetName",target);
        var flags=input.putObject("uncertainty");flags.put("lowEvidence",input.path("diagnosisStatus").asText().equals("LOW_EVIDENCE"));for(String field:List.of("interestTie","lowDifferentiation"))flags.put(field,context.path("result").path("flags").path(field).asBoolean());
        var prefs=input.putArray("preferences");for(JsonNode answer:context.path("support").path("appliedAnswers")){var pref=prefs.addObject();pref.put("topic",answer.path("questionId").asText());pref.put("meaning",answer.path("meaningCode").asText());pref.put("unknown",answer.path("unknown").asBoolean());}
        var collection=input.putObject("collection");collection.put("initialized",context.path("support").path("initialized").asBoolean());collection.put("applicableAnswerCount",prefs.size());collection.put("budgetLimited",context.path("support").path("collectionBudgetLimited").asBoolean());collection.put("omittedQuestionCount",context.path("support").path("omittedQuestionIds").size());
        var evidence=r.putObject("evidence");evidence.put("approvedOnly",true);evidence.put("bindingStatus","NOT_BOUND");evidence.putNull("datasetVersion");evidence.putArray("referenceKeys");
        r.put("inputHash",contract.inputHash(r));contract.request(r);return r;
    }
}
