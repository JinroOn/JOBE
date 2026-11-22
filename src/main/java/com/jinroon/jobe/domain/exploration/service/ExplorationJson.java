package com.jinroon.jobe.domain.exploration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExplorationJson {
    private final ObjectMapper mapper;

    public JsonNode read(String value) {
        try { return mapper.readTree(value); }
        catch (Exception e) { throw ExplorationApiException.integrity(); }
    }

    public JsonNode tree(Object value) { return mapper.valueToTree(value); }
    // MVC uses Jackson 3 in Boot 4; never hand it a Jackson 2 tree as a POJO.
    public Object responseValue(JsonNode value) { return mapper.convertValue(value, Object.class); }
    public ObjectNode object() { return mapper.createObjectNode(); }
    public ArrayNode array() { return mapper.createArrayNode(); }

    public String write(Object value) {
        try { return mapper.writeValueAsString(canonical(tree(value))); }
        catch (Exception e) { throw ExplorationApiException.integrity(); }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = object();
            TreeSet<String> fields = new TreeSet<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.forEach(k -> result.set(k, canonical(value.get(k))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = array();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    public byte[] hash(Object value) {
        try { return MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public String hex(Object value) { return HexFormat.of().formatHex(hash(value)); }
}
