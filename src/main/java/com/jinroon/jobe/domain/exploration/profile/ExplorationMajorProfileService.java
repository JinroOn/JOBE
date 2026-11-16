package com.jinroon.jobe.domain.exploration.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationRunMode;
import com.jinroon.jobe.domain.exploration.service.ExplorationRuntimePolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결과 화면의 "전공 자세히 보기". 탐색형 전공 코드(MAJOR_ROW_*)를 현재 실행 설정의 전공 매핑으로 찾아
 * 그 매핑이 가리키는 전공 서비스 자료(datasets/majors/*.service.json)의 설명 부분만 돌려준다.
 * 점수·가중치·추천 판단 값은 내보내지 않는다. 설명 문구는 외부 출처를 정리한 것이며 내용 검토 전이라 DEV 전용이다.
 */
@Service
@RequiredArgsConstructor
public class ExplorationMajorProfileService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ExplorationRuntimePolicy policy;

    @Value("${exploration.runtime.ruleset-version:RULESET_V1_5_IMPLEMENTATION_CANDIDATE}")
    private String rulesetVersion;

    @Value("${major.dataset.majors-path:datasets/majors}")
    private String majorsPath;

    public record Profile(String majorCode, String name, String category, String description, List<String> keywords,
                          String bestFor, String comparisonNote, List<String> lessCentral, List<String> relatedCareers,
                          String profileVersion, String source, String sourceUrl, String mappingVersion,
                          String domainReviewStatus, String runMode, boolean testOnly) {}

    @Transactional(readOnly = true)
    public Profile profile(long userId, String majorCode) {
        ExplorationRunMode mode = policy.authorize(userId);
        if (!majorCode.matches("MAJOR_ROW_[0-9]{1,9}")) {
            throw new ExplorationApiException(400, "INVALID_REQUEST", "전공 코드 형식을 확인해 주세요.");
        }
        String mappingVersion = runtimeMappingVersion();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT m.mapping_json, m.implementation_review_status, "
                + "m.domain_review_status FROM exploration_major_mapping m JOIN exploration_catalog c ON c.id = m.catalog_id "
                + "WHERE c.catalog_type = 'MAJOR_MAPPING' AND c.version = ? AND m.major_code = ?", mappingVersion, majorCode);
        if (rows.isEmpty() || !"APPROVED_FOR_IMPLEMENTATION".equals(rows.get(0).get("implementation_review_status"))
                || "REJECTED".equals(rows.get(0).get("domain_review_status"))) {
            throw new ExplorationApiException(404, "RESOURCE_NOT_FOUND", "전공 정보를 찾을 수 없습니다.");
        }
        JsonNode mapping = read(rows.get(0).get("mapping_json").toString());
        JsonNode service = serviceFile(majorCode, mapping);

        JsonNode differentiation = service.path("differentiation");
        JsonNode source = service.path("descriptionSources").path(0);
        List<String> careers = new ArrayList<>();
        // 두 가지 원본 형식을 모두 읽는다: relatedOccupations[].occupationName(엑셀 기반) / relatedJobs[](이후 추가된 전공).
        service.path("relatedOccupations").forEach(o -> addCareer(careers, o.path("occupationName").asText("")));
        service.path("relatedJobs").forEach(o -> addCareer(careers, o.asText("")));
        return new Profile(majorCode, service.path("majorName").asText(), text(service.path("category")),
                text(service.path("description")), strings(differentiation.path("focusKeywords")),
                text(differentiation.path("bestFor")), text(differentiation.path("comparisonNote")),
                strings(differentiation.path("lessCentralThanSimilarMajors")), careers,
                text(service.path("metadata").path("datasetVersion")), text(source.path("sourceName")),
                text(source.path("sourceUrl")), mappingVersion, (String) rows.get(0).get("domain_review_status"),
                mode.name(), mode == ExplorationRunMode.DEV);
    }

    /** 실행 설정의 규칙이 가리키는 전공 매핑 버전. 회차 시작과 같은 기준을 쓴다. */
    private String runtimeMappingVersion() {
        List<String> rules = jdbc.queryForList("SELECT rules_json FROM exploration_ruleset WHERE version = ?", String.class, rulesetVersion);
        if (rules.isEmpty()) throw new ExplorationApiException(503, "NO_ACTIVE_CATALOG", "적재된 실행 규칙이 없습니다.");
        String version = read(rules.get(0)).path("catalog_versions").path("major_mapping").asText("");
        if (version.isBlank()) throw ExplorationApiException.integrity();
        return version;
    }

    /**
     * 매핑의 source_file 이름만 쓰고 경로는 서버 설정 폴더로 고정한다.
     * 파일 이름이 전공 코드와 맞지 않거나, 파일의 전공명이 매핑과 다르거나, 검증 상태가 승인이 아니면 내보내지 않는다.
     */
    private JsonNode serviceFile(String majorCode, JsonNode mapping) {
        String file = Path.of(mapping.path("source_file").asText("")).getFileName().toString();
        String prefix = "major-row-" + majorCode.substring("MAJOR_ROW_".length()) + "-";
        Path base = Path.of(majorsPath).toAbsolutePath().normalize();
        Path path = base.resolve(file).normalize();
        if (!file.startsWith(prefix) || !file.endsWith(".service.json") || !path.startsWith(base) || !Files.isRegularFile(path)) {
            throw ExplorationApiException.integrity();
        }
        JsonNode service;
        try {
            service = mapper.readTree(Files.readString(path));
        } catch (IOException e) {
            throw ExplorationApiException.integrity();
        }
        // 검증 기록이 있는 파일은 승인 상태여야 한다. 검증 기록 자체가 없는 이전 형식 파일(1001·1002번)은 이름 대조만 한다.
        JsonNode quality = service.path("validation").path("dataQualityStatus");
        if (!mapping.path("major_name").asText().equals(service.path("majorName").asText())
                || (!quality.isMissingNode() && !"approved".equals(quality.asText()))) {
            throw ExplorationApiException.integrity();
        }
        return service;
    }

    private static void addCareer(List<String> careers, String name) {
        if (!name.isBlank() && !careers.contains(name)) careers.add(name);
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            throw ExplorationApiException.integrity();
        }
    }

    private static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(v -> { if (v.isTextual() && !v.asText().isBlank()) values.add(v.asText()); });
        return values;
    }
}
