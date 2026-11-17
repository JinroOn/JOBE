package com.jinroon.jobe.domain.exploration.ai;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.nio.file.*;
import java.util.stream.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class Plan12ContractTest {
    static final ObjectMapper M=new ObjectMapper();static final ExplorationAiContract C=new ExplorationAiContract(new ExplorationJson(M));
    static JsonNode cases()throws Exception{return M.readTree(Files.readString(Path.of("datasets/exploration-plan12-contract/cases.json")));}
    static Stream<Arguments> requests()throws Exception{return StreamSupport.stream(cases().path("requestCases").spliterator(),false).map(c->Arguments.of(c.path("name").asText(),c));}
    static Stream<Arguments> responses()throws Exception{return StreamSupport.stream(cases().path("responseCases").spliterator(),false).map(c->Arguments.of(c.path("name").asText(),c));}
    @ParameterizedTest @MethodSource("requests") void request(String name,JsonNode c){if(c.path("valid").asBoolean())C.request(c.path("request"));else assertThatThrownBy(()->C.request(c.path("request"))).isInstanceOf(ExplorationAiContract.Invalid.class);}
    @ParameterizedTest @MethodSource("responses") void response(String name,JsonNode c)throws Exception{JsonNode request=cases().path("request");if(c.path("valid").asBoolean())C.response(request,c.path("response"));else assertThatThrownBy(()->C.response(request,c.path("response"))).isInstanceOf(ExplorationAiContract.Invalid.class);}
}
