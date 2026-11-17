package com.jinroon.jobe.domain.exploration.ai;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.nio.file.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class ExplorationConversationContractTest {
    private static final ObjectMapper M=new ObjectMapper();
    private static final ExplorationJson JSON=new ExplorationJson(M);
    private static final ExplorationAiContract CONTRACT=new ExplorationAiContract(JSON);
    private static final ExplorationAiInputAdapter ADAPTER=new ExplorationAiInputAdapter(JSON,CONTRACT);
    static Stream<Arguments> vectors() throws Exception {
        JsonNode cases=M.readTree(Files.readString(Path.of("datasets/exploration-conversation-contract/cases.json")));
        return StreamSupport.stream(cases.path("requestCases").spliterator(),false).map(c->Arguments.of(c.path("name").asText(),c));
    }
    @ParameterizedTest @MethodSource("vectors")
    void sharedWireValidation(String name,JsonNode c){
        if(c.path("valid").asBoolean()){CONTRACT.request(c.path("request"));assertThat(CONTRACT.inputHash(c.path("request"))).isEqualTo(c.path("request").path("inputHash").asText());}
        else assertThatThrownBy(()->CONTRACT.request(c.path("request"))).isInstanceOf(ExplorationAiContract.Invalid.class).hasMessage("Invalid exploration AI contract");
    }
    @Test void adapterCopiesConversationAndPreservesOriginalContext() throws Exception {
        JsonNode cases=M.readTree(Files.readString(Path.of("datasets/exploration-ai-contract/cases.json")));
        var context=cases.path("adapterCases").get(0).path("context");var before=context.deepCopy();
        var conversation=JSON.object().put("question","어떤 자료부터 확인하면 좋을까요?");conversation.putArray("history");
        JsonNode original=ADAPTER.build(context,"original");JsonNode request=ADAPTER.buildConversation(context,"conversation",conversation);
        assertThat(request.path("contractVersion").asText()).isEqualTo(ExplorationAiContract.CONVERSATION_VERSION);
        assertThat(request.path("sourceContextHash")).isEqualTo(original.path("sourceContextHash"));
        assertThat(request.path("inputHash")).isNotEqualTo(original.path("inputHash"));
        assertThat(context).isEqualTo(before);conversation.put("question","변경됨");
        assertThat(request.path("input").path("conversation").path("question").asText()).isEqualTo("어떤 자료부터 확인하면 좋을까요?");
        assertThat(ADAPTER.build(context,"original")).isEqualTo(original);
    }
    @Test void changedQuestionAndResponseVersionCannotReplayAnOldBinding() throws Exception {
        JsonNode vectors=M.readTree(Files.readString(Path.of("datasets/exploration-conversation-contract/cases.json")));
        ObjectNode request=vectors.path("requestCases").get(0).path("request").deepCopy();
        var response=CONTRACT.insufficient(request);CONTRACT.response(request,response);
        response.put("contractVersion",ExplorationAiContract.VERSION);
        assertThatThrownBy(()->CONTRACT.response(request,response)).isInstanceOf(ExplorationAiContract.Invalid.class);
        ((ObjectNode)request.path("input").path("conversation")).put("question","Changed question");
        assertThatThrownBy(()->CONTRACT.request(request)).isInstanceOf(ExplorationAiContract.Invalid.class);
    }
}
