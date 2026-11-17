package com.jinroon.jobe.domain.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.repository.ExplorationResultRepository;
import com.jinroon.jobe.domain.exploration.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ExplorationStoredJsonApiTest extends ExplorationApiTestSupport {
    @Autowired ExplorationStoredJson storage;
    @Autowired ExplorationJson json;
    @Autowired ExplorationResultStore store;
    @Autowired ExplorationResultRepository results;
    @Autowired PlatformTransactionManager transactions;

    @Test void mysqlNumericNormalizationIsSealedBeforeInsertionAndFreshResultReadsRemainValid() throws Exception {
        long session=start("start").path("sessionId").asLong();
        var payload=json.object(); payload.put("sessionId",session);
        payload.put("resultContractVersion",ExplorationResultAssembler.CONTRACT);
        payload.put("status","READY");payload.put("completionReason","NORMAL");
        payload.set("input",json.tree(Map.of("groupScore",61.800000000000004)));
        payload.put("inputHash",json.hex(payload.path("input")));
        payload.put("calculatedScore",61.800000000000004);
        String original=json.write(payload);
        JsonNode mysql=storage.normalize(payload);
        assertThat(json.hex(mysql)).as("The observed MySQL8 JSON float conversion must be exercised").isNotEqualTo(json.hex(payload));
        long id=new TransactionTemplate(transactions).execute(tx->store.save(session,payload).getId());
        // New repository read, not the pre-insert JPA object's unnormalized String.
        JsonNode durable=store.verifiedSnapshot(results.findById(id).orElseThrow());
        assertThat(durable.path("calculatedScore")).isEqualTo(mysql.path("calculatedScore"));
        assertThat(durable.path("inputHash").asText()).isEqualTo(json.hex(durable.path("input")));
        assertThat(json.write(payload)).isEqualTo(original);
        assertThat(store.verifiedSnapshot(results.findById(id).orElseThrow())).isEqualTo(durable);
        jdbc.update("UPDATE exploration_result SET result_json=JSON_SET(result_json,'$.calculatedScore',62) WHERE id=?",id);
        assertThatThrownBy(()->store.verifiedSnapshot(results.findById(id).orElseThrow()))
                .isInstanceOfSatisfying(ExplorationApiException.class,e->assertThat(e.getCode()).isEqualTo("CATALOG_INTEGRITY_ERROR"));
    }

    @Test void storedCheckpointRepresentationKeepsNullZeroAndTinyFloatDifferencesOutOfFalseIntegrityFailures() {
        var value=json.object();value.put("score",61.800000000000004);value.put("zero",0);value.putNull("missing");
        value.put("label","합성 검증"); value.put("enabled",true);
        var frozen=storage.freeze(value);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("CREATE TEMPORARY TABLE audit_json_round_trip(payload JSON,hash CHAR(64))");
            try {
            jdbc.update("INSERT INTO audit_json_round_trip(payload,hash) VALUES (?,?)",frozen.encoded(),frozen.hash());
            JsonNode read=json.read(jdbc.queryForObject("SELECT payload FROM audit_json_round_trip",String.class));
            assertThat(json.hex(read)).isEqualTo(frozen.hash());
            assertThat(read.path("zero").asInt()).isZero();assertThat(read.path("missing").isNull()).isTrue();
            assertThat(storage.freeze(read).hash()).isEqualTo(frozen.hash());
            } finally {jdbc.execute("DROP TEMPORARY TABLE audit_json_round_trip");}
        });
    }
}
