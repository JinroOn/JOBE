package com.jinroon.jobe.domain.major.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MajorService {

    private final MajorRepository majorRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public List<Major> findMajors(String category, String keyword) {
        if (category != null && !category.isBlank()) {
            return majorRepository.findByCategory(category);
        }
        if (keyword != null && !keyword.isBlank()) {
            return majorRepository.findByNameContaining(keyword);
        }
        return majorRepository.findAll();
    }

    public Major getMajor(Long majorId) {
        return get(majorRepository, majorId, ErrorCode.MAJOR_NOT_FOUND);
    }

    private static final java.util.Set<String> LEGACY_FIELDS = java.util.Set.of("name", "category", "difficulty", "description", "careerPaths", "reqMathLogic", "reqProblemSolving", "reqInfoTech", "reqImplementation", "reqSystemUnderstanding", "reqDataAnalysis", "reqCommunication", "reqCollaboration", "reqSelfManagement", "tendLogicalInquiry", "tendPracticalTech", "tendArtCreative", "tendSocialCooperation", "tendLifeHealth", "tendEducationGuide", "tendTheoryAcademic", "tendDataAnalytics", "tendSystemOperation", "thrMathLogic", "thrInfoTech");

    @Transactional
    public Major createMajor(Map<String, Object> values) {
        validateLegacyValues(values);
        if (!(values.get("name") instanceof String) || !(values.get("category") instanceof String)) invalid();
        Map<String,Object> metadata = new java.util.LinkedHashMap<>();
        for (String key : List.of("name","category","difficulty","description","careerPaths"))
            if (values.get(key) != null) metadata.put(key, values.get(key));
        metadata.putIfAbsent("difficulty", "mid");
        Major major = EntityFormMapper.create(Major.class, metadata);
        major.updateLegacyScores(legacyScores(values));
        return majorRepository.save(major);
    }

    @Transactional
    public Major updateMajor(Long majorId, Map<String, Object> values) {
        validateLegacyValues(values);
        // Lock before checking mapping references, as the binding service locks the same major row.
        if (jdbc.queryForList("SELECT id FROM majors WHERE id=? FOR UPDATE", majorId).isEmpty()) {
            throw new com.jinroon.jobe.global.exception.CustomException(ErrorCode.MAJOR_NOT_FOUND);
        }
        Major major = getMajor(majorId);
        String name = (String) values.get("name");
        if (name != null && !name.equals(major.getName()) && jdbc.queryForObject(
                "SELECT COUNT(*) FROM exploration_major_mapping WHERE major_id=?", Long.class, majorId) > 0) {
            throw new com.jinroon.jobe.global.exception.CustomException(ErrorCode.MAJOR_RENAME_REQUIRES_MAPPING_UPDATE);
        }
        major.updateLegacyMetadata(name, (String) values.get("category"), values.get("difficulty") == null ? null
                : com.jinroon.jobe.domain.major.enums.MajorEnums.MajorDifficulty.valueOf((String) values.get("difficulty")));
        major.updateDescription((String) values.get("description"), (String) values.get("careerPaths"));
        major.updateLegacyScores(legacyScores(values));
        return major;
    }

    private static void validateLegacyValues(Map<String,Object> values) {
        if (!LEGACY_FIELDS.containsAll(values.keySet())) invalid();
        for (String key : List.of("name","category")) if (values.get(key) != null
                && (!(values.get(key) instanceof String value) || value.isBlank())) invalid();
        if (values.get("difficulty") != null && !java.util.Set.of("low","mid","high").contains(values.get("difficulty"))) invalid();
        // Validate all scores before mutating an entity, including direct service calls.
        legacyScores(values);
    }
    private static Float legacyValue(Map<String,Object> values,String key) {
        Object value = values.get(key);
        if (value == null) return null;
        if (!(value instanceof Number number) || !Float.isFinite(number.floatValue())
                || number.doubleValue() < 0 || number.doubleValue() > 100) invalid();
        return ((Number) value).floatValue();
    }
    private static Major.LegacyScores legacyScores(Map<String,Object> values) {
        return new Major.LegacyScores(legacyValue(values, "reqMathLogic"),
                legacyValue(values, "reqProblemSolving"),
                legacyValue(values, "reqInfoTech"),
                legacyValue(values, "reqImplementation"),
                legacyValue(values, "reqSystemUnderstanding"),
                legacyValue(values, "reqDataAnalysis"),
                legacyValue(values, "reqCommunication"),
                legacyValue(values, "reqCollaboration"),
                legacyValue(values, "reqSelfManagement"),
                legacyValue(values, "tendLogicalInquiry"),
                legacyValue(values, "tendPracticalTech"),
                legacyValue(values, "tendArtCreative"),
                legacyValue(values, "tendSocialCooperation"),
                legacyValue(values, "tendLifeHealth"),
                legacyValue(values, "tendEducationGuide"),
                legacyValue(values, "tendTheoryAcademic"),
                legacyValue(values, "tendDataAnalytics"),
                legacyValue(values, "tendSystemOperation"),
                legacyValue(values, "thrMathLogic"),
                legacyValue(values, "thrInfoTech"));
    }
    private static void invalid() {
        throw new com.jinroon.jobe.global.exception.CustomException(ErrorCode.INVALID_INPUT);
    }

    @Transactional
    public void deleteMajor(Long majorId) {
        if (jdbc.queryForList("SELECT id FROM majors WHERE id=? FOR UPDATE", majorId).isEmpty()) {
            throw new com.jinroon.jobe.global.exception.CustomException(ErrorCode.MAJOR_NOT_FOUND);
        }
        for (String table : new String[]{"user_favorites", "result_major_scores", "exploration_major_mapping"}) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE major_id=?", Long.class, majorId) > 0) {
                throw new com.jinroon.jobe.global.exception.CustomException(ErrorCode.MAJOR_IN_USE);
            }
        }
        majorRepository.delete(getMajor(majorId));
    }
}
