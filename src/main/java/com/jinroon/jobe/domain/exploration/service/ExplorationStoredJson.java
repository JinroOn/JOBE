package com.jinroon.jobe.domain.exploration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Seal the representation MySQL JSON can durably store, before inserting. Never reseals an existing row. */
@Component
@RequiredArgsConstructor
public class ExplorationStoredJson {
    private final JdbcTemplate jdbc;
    private final ExplorationJson json;

    public record Frozen(JsonNode payload, String encoded, String hash) {}

    public JsonNode normalize(JsonNode value) {
        return json.read(jdbc.queryForObject("SELECT CAST(? AS JSON)", String.class, json.write(value)));
    }

    public Frozen freeze(JsonNode value) {
        JsonNode stored = normalize(value);
        String encoded = json.write(stored), hash = json.hex(stored);
        if (!hash.equals(json.hex(normalize(stored)))) throw ExplorationApiException.integrity();
        return new Frozen(stored, encoded, hash);
    }
}
