package com.jinroon.jobe.domain.diagnosis.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.diagnosis.entity.ExamQuestion;
import com.jinroon.jobe.domain.diagnosis.enums.DiagnosisEnums.CompetencyCategory;
import com.jinroon.jobe.domain.diagnosis.repository.ExamQuestionRepository;
import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "diagnosis.questions.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExamQuestionSeedInitializer implements ApplicationRunner {

    private static final Map<String, CompetencyCategory> AXIS_NAME_MAP = axisNameMap();

    private final ExamQuestionRepository examQuestionRepository;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    @Value("${diagnosis.questions.seed.path:classpath:data/evaluation-questions-separated-60.json}")
    private String seedPath;

    @Value("${diagnosis.questions.seed.default-time-limit-sec:60}")
    private Integer defaultTimeLimitSec;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (examQuestionRepository.count() > 0) {
            log.info("Exam question seed skipped because exam_questions already has data.");
            return;
        }

        Resource resource = resourceLoader.getResource(seedPath);
        if (!resource.exists()) {
            log.warn("Exam question seed file not found path={}", seedPath);
            return;
        }

        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(inputStream);
            List<ExamQuestion> questions = new ArrayList<>();
            for (JsonNode questionNode : root.path("questions")) {
                questions.add(toExamQuestion(questionNode));
            }
            examQuestionRepository.saveAll(questions);
            log.info("Exam question seed imported count={} path={}", questions.size(), seedPath);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read exam question seed file: " + seedPath, exception);
        }
    }

    private ExamQuestion toExamQuestion(JsonNode questionNode) {
        Map<String, String> options = options(questionNode.path("options"));
        EnumMap<CompetencyCategory, Float> weights = weights(questionNode.path("scoreWeight"));
        CompetencyCategory primaryCategory = primaryCategory(weights);

        Map<String, Object> values = new HashMap<>();
        values.put("competencyCategory", primaryCategory);
        values.put("questionText", questionNode.path("question").asText(""));
        values.put("optionA", options.getOrDefault("A", ""));
        values.put("optionB", options.getOrDefault("B", ""));
        values.put("optionC", options.getOrDefault("C", ""));
        values.put("optionD", options.getOrDefault("D", ""));
        values.put("correctAnswer", questionNode.path("answer").asText("A"));
        values.put("timeLimitSec", defaultTimeLimitSec);
        values.put("difficulty", questionNode.path("difficulty").isMissingNode() ? null : questionNode.path("difficulty").asInt());
        values.put("wMathLogic", weights.getOrDefault(CompetencyCategory.math_logic, 0.0f));
        values.put("wProblemSolving", weights.getOrDefault(CompetencyCategory.problem_solving, 0.0f));
        values.put("wInfoTech", weights.getOrDefault(CompetencyCategory.info_tech, 0.0f));
        values.put("wImplementation", weights.getOrDefault(CompetencyCategory.implementation, 0.0f));
        values.put("wSystemUnderstanding", weights.getOrDefault(CompetencyCategory.system_understanding, 0.0f));
        values.put("wDataAnalysis", weights.getOrDefault(CompetencyCategory.data_analysis, 0.0f));
        values.put("wCommunication", weights.getOrDefault(CompetencyCategory.communication, 0.0f));
        values.put("wCollaboration", weights.getOrDefault(CompetencyCategory.collaboration, 0.0f));
        values.put("wSelfManagement", weights.getOrDefault(CompetencyCategory.self_management, 0.0f));
        return EntityFormMapper.create(ExamQuestion.class, values);
    }

    private Map<String, String> options(JsonNode optionsNode) {
        Map<String, String> options = new HashMap<>();
        if (!optionsNode.isArray()) {
            return options;
        }
        for (JsonNode optionNode : optionsNode) {
            String optionId = optionNode.path("optionId").asText("");
            if (!optionId.isBlank()) {
                options.put(optionId, optionNode.path("text").asText(""));
            }
        }
        return options;
    }

    private EnumMap<CompetencyCategory, Float> weights(JsonNode scoreWeightNode) {
        EnumMap<CompetencyCategory, Float> weights = zeroWeights();
        JsonNode competencyNode = scoreWeightNode.has("competency")
                ? scoreWeightNode.path("competency")
                : scoreWeightNode;
        competencyNode.fields().forEachRemaining(entry -> {
            CompetencyCategory category = AXIS_NAME_MAP.get(entry.getKey());
            if (category != null) {
                weights.put(category, (float) entry.getValue().asDouble(0.0));
            }
        });
        return weights;
    }

    private EnumMap<CompetencyCategory, Float> zeroWeights() {
        EnumMap<CompetencyCategory, Float> weights = new EnumMap<>(CompetencyCategory.class);
        for (CompetencyCategory category : CompetencyCategory.values()) {
            weights.put(category, 0.0f);
        }
        return weights;
    }

    private CompetencyCategory primaryCategory(EnumMap<CompetencyCategory, Float> weights) {
        return weights.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(entry -> entry.getValue() > 0.0f)
                .map(Map.Entry::getKey)
                .orElse(CompetencyCategory.math_logic);
    }

    private static Map<String, CompetencyCategory> axisNameMap() {
        Map<String, CompetencyCategory> map = new HashMap<>();
        map.put("수리논리", CompetencyCategory.math_logic);
        map.put("mathLogicalScore", CompetencyCategory.math_logic);
        map.put("math_logic", CompetencyCategory.math_logic);
        map.put("문제해결", CompetencyCategory.problem_solving);
        map.put("problemSolvingScore", CompetencyCategory.problem_solving);
        map.put("problem_solving", CompetencyCategory.problem_solving);
        map.put("정보기술", CompetencyCategory.info_tech);
        map.put("infoTechUtilizationScore", CompetencyCategory.info_tech);
        map.put("info_tech", CompetencyCategory.info_tech);
        map.put("구현력", CompetencyCategory.implementation);
        map.put("softwareImplementationScore", CompetencyCategory.implementation);
        map.put("implementation", CompetencyCategory.implementation);
        map.put("시스템이해", CompetencyCategory.system_understanding);
        map.put("systemUnderstandingScore", CompetencyCategory.system_understanding);
        map.put("system_understanding", CompetencyCategory.system_understanding);
        map.put("데이터분석", CompetencyCategory.data_analysis);
        map.put("dataAnalysisScore", CompetencyCategory.data_analysis);
        map.put("data_analysis", CompetencyCategory.data_analysis);
        map.put("의사소통", CompetencyCategory.communication);
        map.put("communicationScore", CompetencyCategory.communication);
        map.put("communication", CompetencyCategory.communication);
        map.put("협업윤리", CompetencyCategory.collaboration);
        map.put("collaborationScore", CompetencyCategory.collaboration);
        map.put("collaboration", CompetencyCategory.collaboration);
        map.put("자기관리", CompetencyCategory.self_management);
        map.put("selfManagementScore", CompetencyCategory.self_management);
        map.put("self_management", CompetencyCategory.self_management);
        return map;
    }
}
