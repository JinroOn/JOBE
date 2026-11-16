package com.jinroon.jobe.domain.exploration.display;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Versioned DEV wording, not evidence of human content approval. Keep released resources immutable. */
@Component
public class ResultDisplayCatalog {
    public static final String VERSION = "RESULT_DISPLAY_V1_0";
    private final JsonNode content;

    public ResultDisplayCatalog(ObjectMapper mapper) throws java.io.IOException {
        try (var stream = new ClassPathResource("exploration/result-display-v1.json").getInputStream()) {
            content = mapper.readTree(stream);
        }
        if (!VERSION.equals(content.path("version").asText()) || !"DEV".equals(content.path("scope").asText()))
            throw new IllegalStateException("Unsupported result display catalog");
    }

    public JsonNode snapshot() { return content.deepCopy(); }
}
