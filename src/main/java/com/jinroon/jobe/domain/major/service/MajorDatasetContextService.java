package com.jinroon.jobe.domain.major.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest;
import com.jinroon.jobe.global.client.dto.request.WeeklyPlanRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MajorDatasetContextService {

    private static final int MAX_RELATED_JOBS = 8;
    private static final int MAX_RAG_SNIPPETS = 6;
    private static final int MAX_SNIPPET_LENGTH = 500;

    private final ObjectMapper objectMapper;

    @Value("${major.dataset.majors-path:datasets/majors}")
    private String majorsPath;

    @Value("${major.dataset.rag-path:datasets/rag/per-major}")
    private String ragPath;

    public RecommendationCommentRequest.MajorContext toRecommendationMajorContext(Major major) {
        DatasetContext context = datasetContext(major);
        return new RecommendationCommentRequest.MajorContext(
                context.category(),
                context.description(),
                context.sourceSummary(),
                context.relatedJobs(),
                context.ragSnippets()
        );
    }

    public WeeklyPlanRequest.MajorContext toWeeklyPlanMajorContext(Major major) {
        DatasetContext context = datasetContext(major);
        return new WeeklyPlanRequest.MajorContext(
                context.category(),
                context.description(),
                context.sourceSummary(),
                context.relatedJobs(),
                context.ragSnippets()
        );
    }

    private DatasetContext datasetContext(Major major) {
        Optional<JsonNode> serviceNode = findServiceJson(major);
        JsonNode node = serviceNode.orElse(null);
        return new DatasetContext(
                firstText(node, major.getCategory(), "category"),
                firstText(node, major.getDescription(), "description", "differentiation.comparisonNote"),
                sourceSummary(node, major),
                relatedJobs(node, major),
                ragSnippets(major)
        );
    }

    private Optional<JsonNode> findServiceJson(Major major) {
        Path dir = Path.of(majorsPath);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        String normalizedMajorName = normalize(major.getName());
        try (Stream<Path> paths = Files.list(dir)) {
            List<Path> candidates = paths
                    .filter(path -> path.getFileName().toString().endsWith(".service.json"))
                    .toList();

            for (Path path : candidates) {
                if (normalize(path.getFileName().toString()).contains(normalizedMajorName)) {
                    return readJson(path);
                }
            }
            for (Path path : candidates) {
                Optional<JsonNode> json = readJson(path);
                if (json.isPresent() && jsonMajorNameMatches(json.get(), normalizedMajorName)) {
                    return json;
                }
            }
        } catch (IOException e) {
            log.warn("전공 service dataset 디렉토리 조회 실패 path={} error={}", dir, e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<JsonNode> readJson(Path path) {
        try {
            return Optional.of(objectMapper.readTree(path.toFile()));
        } catch (IOException e) {
            log.warn("전공 service dataset JSON 파싱 실패 file={} error={}", path.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    private boolean jsonMajorNameMatches(JsonNode node, String normalizedMajorName) {
        return List.of("majorName", "standardMajorName", "name").stream()
                .map(node::path)
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText)
                .map(this::normalize)
                .anyMatch(normalizedMajorName::equals);
    }

    private String sourceSummary(JsonNode node, Major major) {
        String summary = firstText(
                node,
                null,
                "sourceSummary",
                "differentiation.comparisonNote",
                "weightSelectionPolicy.reason"
        );
        if (summary != null && !summary.isBlank()) {
            return summary;
        }
        if (major.getDescription() != null && !major.getDescription().isBlank()) {
            return major.getName() + " 전공 설명을 기반으로 구성한 기본 컨텍스트입니다.";
        }
        return null;
    }

    private List<String> relatedJobs(JsonNode node, Major major) {
        List<String> jobs = new ArrayList<>();
        addTextValues(jobs, node, "relatedJobs");
        addTextValues(jobs, node, "careerPaths");
        addOccupationValues(jobs, node);
        if (jobs.isEmpty()) {
            addDelimitedValues(jobs, major.getCareerPaths());
        }
        return jobs.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .limit(MAX_RELATED_JOBS)
                .toList();
    }

    private void addTextValues(List<String> values, JsonNode node, String path) {
        JsonNode target = at(node, path);
        if (target == null || target.isMissingNode() || target.isNull()) {
            return;
        }
        if (target.isArray()) {
            target.forEach(item -> {
                if (item.isTextual()) {
                    values.add(item.asText());
                }
            });
            return;
        }
        if (target.isTextual()) {
            addDelimitedValues(values, target.asText());
        }
    }

    private void addOccupationValues(List<String> values, JsonNode node) {
        JsonNode occupations = at(node, "relatedOccupations");
        if (occupations == null || !occupations.isArray()) {
            return;
        }
        occupations.forEach(occupation -> {
            String name = firstText(occupation, null, "occupationName", "name", "title", "jobName");
            if (name != null && !name.isBlank()) {
                values.add(name);
            }
        });
    }

    private void addDelimitedValues(List<String> values, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String value : raw.split("[,;/\\n]")) {
            String trimmed = value.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
    }

    private List<String> ragSnippets(Major major) {
        Path ragFile = findRagFile(major).orElse(null);
        if (ragFile == null) {
            return List.of();
        }
        List<String> snippets = new ArrayList<>();
        try (Stream<String> lines = Files.lines(ragFile, StandardCharsets.UTF_8)) {
            lines.map(String::trim)
                    .filter(line -> !line.isBlank())
                    .forEach(line -> addSnippet(snippets, ragFile, line));
        } catch (IOException e) {
            log.warn("전공 RAG JSONL 조회 실패 file={} error={}", ragFile.getFileName(), e.getMessage());
        }
        return snippets.stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(MAX_RAG_SNIPPETS)
                .toList();
    }

    private Optional<Path> findRagFile(Major major) {
        Path dir = Path.of(ragPath);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        String normalizedMajorName = normalize(major.getName());
        try (Stream<Path> paths = Files.list(dir)) {
            List<Path> candidates = paths
                    .filter(path -> path.getFileName().toString().endsWith(".rag.jsonl"))
                    .toList();

            for (Path path : candidates) {
                if (!normalizedMajorName.isBlank()
                        && normalize(path.getFileName().toString()).contains(normalizedMajorName)) {
                    return Optional.of(path);
                }
            }
            for (Path path : candidates) {
                if (ragJsonlMajorNameMatches(path, normalizedMajorName)) {
                    return Optional.of(path);
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            log.warn("전공 RAG dataset 디렉토리 조회 실패 path={} error={}", dir, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean ragJsonlMajorNameMatches(Path path, String normalizedMajorName) {
        if (normalizedMajorName == null || normalizedMajorName.isBlank()) {
            return false;
        }
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .limit(5)
                    .map(this::readJsonLine)
                    .flatMap(Optional::stream)
                    .anyMatch(node -> jsonMajorNameMatches(node, normalizedMajorName));
        } catch (IOException e) {
            log.warn("?꾧났 RAG JSONL 留ㅼ묶 ?ㅽ뙣 file={} error={}", path.getFileName(), e.getMessage());
            return false;
        }
    }

    private Optional<JsonNode> readJsonLine(String line) {
        try {
            return Optional.of(objectMapper.readTree(line));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void addSnippet(List<String> snippets, Path file, String line) {
        if (snippets.size() >= MAX_RAG_SNIPPETS) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(line);
            String text = firstText(node, null, "text", "content", "summary", "description", "chunkText");
            if (text != null && !text.isBlank()) {
                snippets.add(truncate(text, MAX_SNIPPET_LENGTH));
            }
        } catch (IOException e) {
            log.warn("전공 RAG JSONL 라인 파싱 실패 file={} error={}", file.getFileName(), e.getMessage());
        }
    }

    private String firstText(JsonNode node, String fallback, String... paths) {
        if (node == null) {
            return fallback;
        }
        for (String path : paths) {
            JsonNode value = at(node, path);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return fallback;
    }

    private JsonNode at(JsonNode node, String dottedPath) {
        if (node == null || dottedPath == null) {
            return null;
        }
        JsonNode current = node;
        for (String part : dottedPath.split("\\.")) {
            current = current.path(part);
        }
        return current;
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]", "").toLowerCase();
    }

    private record DatasetContext(
            String category,
            String description,
            String sourceSummary,
            List<String> relatedJobs,
            List<String> ragSnippets
    ) {
    }
}
