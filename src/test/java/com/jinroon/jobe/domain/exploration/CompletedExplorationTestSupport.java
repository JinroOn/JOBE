package com.jinroon.jobe.domain.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Real completed result fixture; seed answers only to keep unrelated support tests small. */
abstract class CompletedExplorationTestSupport extends ExplorationApiTestSupport {
    protected long completedResult(boolean candidates) throws Exception {
        return completedResult(candidates,null);
    }
    protected long completedResult(Map<String,Integer> interestScores) throws Exception {
        return completedResult(true,interestScores);
    }
    private long completedResult(boolean candidates,Map<String,Integer> interestScores) throws Exception {
        long id=start(UUID.randomUUID().toString()).path("sessionId").asLong();
        for(int stage=1;stage<=3;stage++) {
            for(int n=0;n<4;n++) {
                JsonNode next=postJson(id+"/next",owner,UUID.randomUUID().toString(),revisionBody(sessionRevision(id)),200);
                int changed=0;
                for(JsonNode q:next.path("issuedQuestions")) {
                    long iid=q.path("issuedId").asLong();
                    JsonNode snapshot=mapper.readTree(jdbc.queryForObject("SELECT snapshot_json FROM exploration_issued_question WHERE id=?",String.class,iid));
                    String code=q.path("options").get(0).path("code").asText();
                    String value="75";
                    if(interestScores!=null && snapshot.path("question").path("scoring_role").asText().equals("CANDIDATE_TOPIC"))value=interestScores.getOrDefault(snapshot.path("question").path("metric_code").asText(),0).toString();
                    for(JsonNode o:snapshot.path("options"))if(candidates?o.path("numeric_value").asText().equals(value):
                            o.path("is_unknown").asBoolean()||o.path("is_no_experience").asBoolean()){code=o.path("option_code").asText();break;}
                    jdbc.update("INSERT INTO exploration_answer_revision(issued_id,answer_revision,option_code,is_current,client_mutation_id,mutation_request_hash,mutation_response_json,answered_at) VALUES (?,1,?,true,?,?,JSON_OBJECT(),NOW())",
                            iid,code,UUID.randomUUID().toString(),new byte[32]);changed++;
                }
                jdbc.update("UPDATE exploration_session SET revision=revision+? WHERE id=?",changed,id);
                String action=getJson("/"+id,owner,200).path("nextAction").asText();
                if(action.equals("ADVANCE")||action.equals("COMPLETE"))break;
            }
            if(stage<3)postJson(id+"/advance",owner,UUID.randomUUID().toString(),revisionBody(sessionRevision(id)),200);
        }
        return postJson(id+"/complete",owner,UUID.randomUUID().toString(),revisionBody(sessionRevision(id)),200).path("resultId").asLong();
    }
    protected long sessionRevision(long id){return jdbc.queryForObject("SELECT revision FROM exploration_session WHERE id=?",Long.class,id);}
    private String revisionBody(long revision)throws Exception{return mapper.writeValueAsString(Map.of("expectedRevision",revision));}
    protected JsonNode resultSnapshot(long result)throws Exception{return mapper.readTree(jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,result));}
}
