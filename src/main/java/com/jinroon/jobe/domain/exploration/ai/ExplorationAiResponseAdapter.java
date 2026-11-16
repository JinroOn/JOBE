package com.jinroon.jobe.domain.exploration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Classifies wire outcomes without persisting or exposing rejected model output. No legacy fallback. */
@Component
@RequiredArgsConstructor
public class ExplorationAiResponseAdapter {
    private final ExplorationAiContract contract;
    public record Outcome(String status,String reasonCode,boolean retryable,JsonNode result){}
    public Outcome adapt(JsonNode expected,JsonNode actual){
        try{contract.request(expected);}catch(ExplorationAiContract.Invalid e){return new Outcome("INVALID_INPUT","REQUEST_CONTRACT_INVALID",false,null);}
        try{contract.response(expected,actual);}catch(ExplorationAiContract.Invalid e){return new Outcome("INVALID_RESPONSE","RESPONSE_CONTRACT_INVALID",false,null);}
        String status=actual.path("status").asText();return new Outcome(status.equals("SUCCEEDED")?"CONTRACT_VALID":status,actual.path("reasonCode").textValue(),false,actual.path("result").isNull()?null:actual.path("result").deepCopy());
    }
}
