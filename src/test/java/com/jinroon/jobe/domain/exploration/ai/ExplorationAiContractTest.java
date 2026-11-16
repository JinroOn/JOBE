package com.jinroon.jobe.domain.exploration.ai;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class ExplorationAiContractTest {
    private static final ObjectMapper M=new ObjectMapper();
    private static final ExplorationJson JSON=new ExplorationJson(M);
    private static final ExplorationAiContract CONTRACT=new ExplorationAiContract(JSON);
    private static final ExplorationAiInputAdapter INPUT=new ExplorationAiInputAdapter(JSON,CONTRACT);
    private static final ExplorationAiResponseAdapter OUTPUT=new ExplorationAiResponseAdapter(CONTRACT);
    private static JsonNode cases;
    private static Map<String,JsonNode> requests;
    @BeforeAll static void load()throws Exception{cases=M.readTree(Files.readString(Path.of("datasets/exploration-ai-contract/cases.json")));requests=new HashMap<>();cases.path("requestCases").forEach(c->requests.put(c.path("name").asText(),c.path("request")));}
    private static Stream<Arguments> rows(String group){List<Arguments> rows=new ArrayList<>();cases.path(group).forEach(c->rows.add(Arguments.of(c.path("name").asText(),c)));return rows.stream();}
    static Stream<Arguments> adapters(){return rows("adapterCases");}
    static Stream<Arguments> inputs(){return rows("requestCases");}
    static Stream<Arguments> outputs(){return rows("responseCases");}
    @ParameterizedTest @MethodSource("adapters") void adapterMatchesSharedCanonicalFixture(String name,JsonNode sample){assertThat(INPUT.build(sample.path("context"),sample.path("requestId").asText())).isEqualTo(sample.path("expectedRequest"));}
    @ParameterizedTest @MethodSource("inputs") void validatesSharedRequests(String name,JsonNode sample){if(sample.path("valid").asBoolean())CONTRACT.request(sample.path("request"));else assertThatThrownBy(()->CONTRACT.request(sample.path("request"))).isInstanceOf(ExplorationAiContract.Invalid.class);}
    @ParameterizedTest @MethodSource("outputs") void validatesSharedResponses(String name,JsonNode sample){JsonNode expected=requests.get(sample.path("requestName").asText());if(sample.path("valid").asBoolean())CONTRACT.response(expected,sample.path("response"));else assertThatThrownBy(()->CONTRACT.response(expected,sample.path("response"))).isInstanceOf(ExplorationAiContract.Invalid.class);}
    @Test void rejectedOutputNeverBecomesContentOrAnExceptionWithPrivateBody(){JsonNode request=requests.get("consultation");ObjectNode response=CONTRACT.insufficient(request);response.put("email","SYNTHETIC_PRIVATE_OUTPUT");var outcome=OUTPUT.adapt(request,response);assertThat(outcome.status()).isEqualTo("INVALID_RESPONSE");assertThat(outcome.result()).isNull();assertThat(outcome.toString()).doesNotContain("SYNTHETIC_PRIVATE_OUTPUT");}
    @Test void nullBodyAndInvalidExpectedInputAreClassifiedWithoutLegacyFallback(){assertThat(OUTPUT.adapt(requests.get("consultation"),null).status()).isEqualTo("INVALID_RESPONSE");assertThat(OUTPUT.adapt(null,null).status()).isEqualTo("INVALID_INPUT");}
    @Test void insufficientEvidenceIsDeterministicAndSuccessCannotBeForgedForCurrentAdapter(){JsonNode request=INPUT.build(cases.path("adapterCases").get(0).path("context"),"fresh-request");JsonNode response=CONTRACT.insufficient(request);assertThat(OUTPUT.adapt(request,response).status()).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(OUTPUT.adapt(request,response).result()).isNull();assertThat(request.path("evidence").path("bindingStatus").asText()).isEqualTo("NOT_BOUND");}
    @Test void legacyOrUnknownSemanticVersionsAreNotSilentlyConverted(){ObjectNode context=cases.path("adapterCases").get(0).path("context").deepCopy();context.put("resultType","LEGACY");assertThatThrownBy(()->INPUT.build(context,"request")).isInstanceOf(ExplorationAiContract.Invalid.class);context.put("resultType","EXPLORATION_V1");((ObjectNode)context.path("source").path("versions")).put("semanticContract","UNKNOWN_VERSION");assertThatThrownBy(()->INPUT.build(context,"request")).isInstanceOf(ExplorationAiContract.Invalid.class);}
}
