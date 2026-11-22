package com.jinroon.jobe.domain.exploration.display;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** The wording is pinned with the session so saved and resumed questions retain their original text. */
@Component
public class ComparisonQuestionDisplayCatalog {
    private static final String VERSION = "COMPARISON_QUESTION_DISPLAY_V1_0";
    private static final String COMPARE_PROMPT = " 분야를 비교할 때 ";
    private final JsonNode content;

    public ComparisonQuestionDisplayCatalog(ObjectMapper mapper) throws IOException {
        try (var stream = new ClassPathResource("exploration/comparison-question-display-v1.json").getInputStream()) {
            content = mapper.readTree(stream);
        }
        if (!VERSION.equals(content.path("version").asText()) || !content.path("labels").isObject()) {
            throw new IllegalStateException("Unsupported comparison question display catalog");
        }
    }

    public JsonNode snapshot() {
        return content.deepCopy();
    }

    public static String text(JsonNode pinned, String comparisonSet, String original) {
        String label = pinned.path("labels").path(comparisonSet).textValue();
        if (label == null || label.isBlank()) return original;
        int start = original.indexOf(COMPARE_PROMPT);
        return start < 0 ? label + " 분야에서 " + original : label + original.substring(start);
    }
}
