package com.jinroon.jobe.domain.exploration.display;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Versioned DEV wording, not evidence of human content approval. Keep released resources immutable.
 * V1_1 corrects the MED group label to 미디어·콘텐츠 (JOBE #136). Results completed with V1_0 keep their
 * sealed wording, and results sealed before display support keep projecting with V1_0 so they stay reproducible.
 */
@Component
public class ResultDisplayCatalog {
    public static final String VERSION = "RESULT_DISPLAY_V1_1";
    public static final String LEGACY_VERSION = "RESULT_DISPLAY_V1_0";
    private final JsonNode content;
    private final JsonNode legacyContent;

    public ResultDisplayCatalog(ObjectMapper mapper) throws java.io.IOException {
        content = load(mapper, "exploration/result-display-v1-1.json", VERSION);
        legacyContent = load(mapper, "exploration/result-display-v1.json", LEGACY_VERSION);
    }

    private static JsonNode load(ObjectMapper mapper, String path, String version) throws java.io.IOException {
        JsonNode node;
        try (var stream = new ClassPathResource(path).getInputStream()) {
            node = mapper.readTree(stream);
        }
        if (!version.equals(node.path("version").asText()) || !"DEV".equals(node.path("scope").asText()))
            throw new IllegalStateException("Unsupported result display catalog");
        return node;
    }

    /** Wording frozen into new sessions and completions. */
    public JsonNode snapshot() { return content.deepCopy(); }

    /** Wording for results sealed before display support. Never changes, so old projections stay reproducible. */
    public JsonNode legacySnapshot() { return legacyContent.deepCopy(); }
}
