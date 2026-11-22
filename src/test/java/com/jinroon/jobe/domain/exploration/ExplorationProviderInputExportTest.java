package com.jinroon.jobe.domain.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.ai.ExplorationAiInputAdapter;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import com.jinroon.jobe.domain.exploration.support.PostResultSupportService;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** No credentials/provider calls. All answers use issued HTTP; output is synthetic, not a student evaluation. */
@Tag("exploration-provider-input")
class ExplorationProviderInputExportTest extends ExplorationApiTestSupport {
    @Autowired ResultContextService contexts;
    @Autowired PostResultSupportService support;
    @Autowired ExplorationAiInputAdapter adapter;

    @Test void exportOwnedTargetedConversationAndTwelveWeekPlan() throws Exception {
        String configured=System.getProperty("exploration.provider.input-dir", "");
        assertThat(configured).as("An explicit private output directory is required").isNotBlank();
        Path directory=Path.of(configured).toAbsolutePath().normalize();
        Path repo=Path.of("").toRealPath();
        Files.createDirectories(directory);
        assertThat(directory.toRealPath().startsWith(repo)).as("Keep evaluation artifacts outside the repository").isFalse();
        try(var files=Files.list(directory)){assertThat(files.findAny()).as("Use a new empty directory").isEmpty();}
        JsonNode recipes=mapper.readTree(Files.readString(Path.of("datasets/exploration-validation/reachability-profiles.json")));
        JsonNode recipe=null;
        for(JsonNode row:recipes.path("profiles"))if(row.path("id").asText().equals("MAJOR_ROW_133:baseline"))recipe=row;
        assertThat(recipe).isNotNull();
        long sid=start(UUID.randomUUID().toString()).path("sessionId").asLong(),revision=0;
        Map<String,Integer> counts=new HashMap<>();int answered=0;
        for(int stage=1;stage<=3;stage++) {
            boolean ready=false;
            for(int batch=0;batch<6;batch++) {
                JsonNode next=postJson(sid+"/next",owner,UUID.randomUUID().toString(),body(Map.of("expectedRevision",revision)),200);
                revision=next.path("revision").asLong();
                for(JsonNode q:next.path("issuedQuestions")) {
                    long iid=q.path("issuedId").asLong();
                    JsonNode issued=mapper.readTree(jdbc.queryForObject("SELECT snapshot_json FROM exploration_issued_question WHERE id=? AND session_id=?",String.class,iid,sid));
                    String code=choose(recipe,issued,counts);
                    assertThat(q.path("options").findValuesAsText("code")).contains(code);
                    JsonNode saved=response(put(API+"/"+sid+"/answers/"+iid).header("Authorization",owner)
                        .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("expectedRevision",revision,"optionCode",code,"clientMutationId",UUID.randomUUID().toString()))),200);
                    revision=saved.path("revision").asLong();answered++;
                }
                String action=getJson("/"+sid,owner,200).path("nextAction").asText();
                if(Set.of("ADVANCE","COMPLETE").contains(action)){ready=true;break;}
            }
            assertThat(ready).as("Bounded HTTP stage completion").isTrue();
            if(stage<3)revision=postJson(sid+"/advance",owner,UUID.randomUUID().toString(),body(Map.of("expectedRevision",revision)),200)
                .path("session").path("revision").asLong();
        }
        long rid=postJson(sid+"/complete",owner,UUID.randomUUID().toString(),body(Map.of("expectedRevision",revision)),200).path("resultId").asLong();
        String original=jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,rid);
        assertThat(mapper.readTree(original).path("rankedCandidates").findValuesAsText("majorCode")).contains("MAJOR_ROW_133");
        support.select(OWNER,rid,"evaluation-support",0,"BOTH","MAJOR_ROW_133");
        var hashes=mapper.createObjectNode();
        for(String purpose:List.of("CONSULTATION","PLAN")) {
            JsonNode prepared=response(post("/api/exploration/v1/results/"+rid+"/contexts").header("Authorization",owner)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("contextType",purpose,"expectedSupportRevision",1,"targetMajorCode","MAJOR_ROW_133"))),201);
            long cid=prepared.path("contextId").asLong();
            assertThatThrownBy(()->contexts.readForAdapter(OTHER,cid,purpose)).isInstanceOf(RuntimeException.class);
            JsonNode context=contexts.readForAdapter(OWNER,cid,purpose).snapshot();
            JsonNode request;
            if(purpose.equals("PLAN"))request=adapter.buildPlan(context,"eval_plan37");
            else {
                var conversation=mapper.createObjectNode().put("question","컴퓨터공학과에서 무엇을 배우는지 알아보고 싶어요. 처음 확인할 내용과 작은 탐색 활동을 알려주세요.");
                conversation.putArray("history");
                request=adapter.buildConversation(context,"eval_consult37",conversation);
            }
            assertThat(request.path("input").path("targetName").asText()).isEqualTo("컴퓨터공학과");
            assertThat(request.path("evidence").path("bindingStatus").asText()).isEqualTo("NOT_BOUND");
            String file=purpose.toLowerCase(Locale.ROOT)+"-request.json";
            Files.writeString(directory.resolve(file),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(request),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            hashes.put(file,hex(Files.readAllBytes(directory.resolve(file))));
        }
        assertThat(jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,rid)).isEqualTo(original);
        var provenance=mapper.createObjectNode().put("syntheticAccount",true).put("issuedHttpAnswers",answered)
            .put("sourceRecipe","MAJOR_ROW_133:baseline").put("ownedContextChecked",true)
            .put("otherOwnerRejected",true).put("sourceResultUnchanged",true).put("candidateListModified",false)
            .put("providerCalls",0).put("studentEvaluation",false).put("fixtureAccountCleanedAfterExport",true)
            .put("scope","Server-derived synthetic export for read-only provider evaluation; not live consumer E2E.");
        provenance.set("fileSha256",hashes);
        Files.writeString(directory.resolve("provenance.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(provenance),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }

    private String choose(JsonNode recipe,JsonNode snapshot,Map<String,Integer> counts) {
        JsonNode q=snapshot.path("question");String role=q.path("scoring_role").asText(),module=q.path("module_code").asText();
        List<JsonNode> options=new ArrayList<>();snapshot.path("options").forEach(options::add);
        java.util.function.Predicate<JsonNode> unknown=o->o.path("is_unknown").asBoolean()||o.path("is_no_experience").asBoolean();
        if(module.equals("STATE"))return options.stream().filter(o->o.path("meaning_code").asText().equals("STATE_BROAD")).findFirst().orElseThrow().path("option_code").asText();
        if(Set.of("CANDIDATE_TOPIC","CANDIDATE_ACTIVITY","CANDIDATE_LEARNING").contains(role)||module.equals("SUBJECT_CONFIDENCE")) {
            String metric=q.path("metric_code").asText(),type=role.equals("CANDIDATE_TOPIC")?"interest":role.equals("CANDIDATE_ACTIVITY")?"activity":"learning";
            JsonNode target=recipe.path(type+"_targets").path(metric);
            Integer value=target.isNull()?null:target.isMissingNode()?50:target.intValue();
            if(role.equals("CANDIDATE_TOPIC")&&counts.getOrDefault(metric,0)>=recipe.path("interest_response_count_target").asInt())value=null;
            final Integer selected=value;
            JsonNode option=options.stream().filter(o->selected==null?unknown.test(o):Integer.toString(selected).equals(o.path("numeric_value").asText())).findFirst().orElseThrow();
            if(role.equals("CANDIDATE_TOPIC")&&selected!=null)counts.merge(metric,1,Integer::sum);
            return option.path("option_code").asText();
        }
        return options.stream().filter(unknown).findFirst().orElse(options.get(0)).path("option_code").asText();
    }
    private String body(Object value)throws Exception{return mapper.writeValueAsString(value);}
    private String hex(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
