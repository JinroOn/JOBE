package com.jinroon.jobe.domain.major.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.global.client.dto.request.RecommendationCommentRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class MajorDatasetContextServiceTest {

    @TempDir
    private Path tempDir;

    private Path majorsDir;
    private Path ragDir;
    private MajorDatasetContextService service;

    @BeforeEach
    void setUp() throws Exception {
        majorsDir = Files.createDirectories(tempDir.resolve("datasets/majors"));
        ragDir = Files.createDirectories(tempDir.resolve("datasets/rag/per-major"));
        service = new MajorDatasetContextService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "majorsPath", majorsDir.toString());
        ReflectionTestUtils.setField(service, "ragPath", ragDir.toString());
    }

    @Test
    void readsServiceJsonAndRagSnippets() throws Exception {
        Files.writeString(majorsDir.resolve("major-row-1-컴퓨터공학과.service.json"), """
                {
                  "majorName": "컴퓨터공학과",
                  "category": "공학계열",
                  "description": "컴퓨터 시스템과 소프트웨어를 다루는 전공",
                  "sourceSummary": "엑셀 기준 정량 데이터셋",
                  "relatedJobs": ["소프트웨어 개발자", "AI 엔지니어"]
                }
                """);
        Files.writeString(ragDir.resolve("major-row-1-컴퓨터공학과.rag.jsonl"), """
                {"content":"컴퓨터공학과 RAG 1"}
                {"text":"컴퓨터공학과 RAG 2"}
                not-json
                {"summary":"컴퓨터공학과 RAG 3"}
                {"description":"컴퓨터공학과 RAG 4"}
                {"chunkText":"컴퓨터공학과 RAG 5"}
                {"content":"컴퓨터공학과 RAG 6"}
                {"content":"컴퓨터공학과 RAG 7"}
                """);

        RecommendationCommentRequest.MajorContext context =
                service.toRecommendationMajorContext(major("컴퓨터공학과", "기본분류", "기본설명", "기본직업"));

        assertThat(context.category()).isEqualTo("공학계열");
        assertThat(context.description()).isEqualTo("컴퓨터 시스템과 소프트웨어를 다루는 전공");
        assertThat(context.sourceSummary()).isEqualTo("엑셀 기준 정량 데이터셋");
        assertThat(context.relatedJobs()).containsExactly("소프트웨어 개발자", "AI 엔지니어");
        assertThat(context.ragSnippets()).hasSize(6);
        assertThat(context.ragSnippets()).contains("컴퓨터공학과 RAG 1", "컴퓨터공학과 RAG 6");
    }

    @Test
    void returnsFallbackContextWhenDatasetFilesAreMissing() {
        RecommendationCommentRequest.MajorContext context =
                service.toRecommendationMajorContext(major("철학과", "인문계열", "철학 전공 설명", "연구원,교사"));

        assertThat(context.category()).isEqualTo("인문계열");
        assertThat(context.description()).isEqualTo("철학 전공 설명");
        assertThat(context.relatedJobs()).containsExactly("연구원", "교사");
        assertThat(context.ragSnippets()).isEmpty();
    }

    private Major major(String name, String category, String description, String careerPaths) {
        Major major = newEntity(Major.class);
        ReflectionTestUtils.setField(major, "name", name);
        ReflectionTestUtils.setField(major, "category", category);
        ReflectionTestUtils.setField(major, "description", description);
        ReflectionTestUtils.setField(major, "careerPaths", careerPaths);
        return major;
    }

    private <T> T newEntity(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
