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

    @Test
    void matchesExactSupplementedDatasetByMajorName() throws Exception {
        Files.writeString(majorsDir.resolve("major-row-1001-소프트웨어학과.service.json"), """
                {
                  "majorName": "소프트웨어학과",
                  "standardMajorName": "소프트웨어학과",
                  "category": "공학계열",
                  "description": "서비스 구현과 응용 개발 프로젝트를 중심으로 배우는 전공",
                  "sourceSummary": "소프트웨어 구현력과 문제해결 역량을 중심으로 구성한 보강 데이터셋",
                  "relatedJobs": ["소프트웨어개발자", "백엔드개발자", "앱개발자"]
                }
                """);
        Files.writeString(ragDir.resolve("major-row-1001-소프트웨어학과.rag.jsonl"), """
                {"content":"소프트웨어학과 snippet 1"}
                {"content":"소프트웨어학과 snippet 2"}
                {"content":"소프트웨어학과 snippet 3"}
                {"content":"소프트웨어학과 snippet 4"}
                {"content":"소프트웨어학과 snippet 5"}
                {"content":"소프트웨어학과 snippet 6"}
                {"content":"소프트웨어학과 snippet 7"}
                """);

        RecommendationCommentRequest.MajorContext context =
                service.toRecommendationMajorContext(major("소프트웨어학과", "기본분류", "기본설명", null));

        assertThat(context.category()).isEqualTo("공학계열");
        assertThat(context.description()).contains("서비스 구현");
        assertThat(context.sourceSummary()).contains("소프트웨어 구현력");
        assertThat(context.relatedJobs()).containsExactly("소프트웨어개발자", "백엔드개발자", "앱개발자");
        assertThat(context.ragSnippets()).hasSize(6);
        assertThat(context.ragSnippets()).containsExactly(
                "소프트웨어학과 snippet 1",
                "소프트웨어학과 snippet 2",
                "소프트웨어학과 snippet 3",
                "소프트웨어학과 snippet 4",
                "소프트웨어학과 snippet 5",
                "소프트웨어학과 snippet 6"
        );
    }

    @Test
    void doesNotUseAnotherMajorWhoseFileNameContainsTheName() throws Exception {
        // "연극영화학과"에는 "화학과"가 들어 있다. 예전에는 화학과에 연극영화학과 자료가 쓰였다.
        writeMajor("major-row-244-연극영화학과", "연극영화학과", "연극과 영화를 만드는 전공");

        RecommendationCommentRequest.MajorContext context =
                service.toRecommendationMajorContext(major("화학과", "자연계열", "화학 전공 설명", null));

        assertThat(context.description()).isEqualTo("화학 전공 설명");
        assertThat(context.ragSnippets()).isEmpty();
    }

    @Test
    void picksOwnFileWhenSimilarNamesExist() throws Exception {
        writeMajor("major-row-3-국사학과", "국사학과", "한국사 전공");
        writeMajor("major-row-26-사학과", "사학과", "역사 전공");

        RecommendationCommentRequest.MajorContext context =
                service.toRecommendationMajorContext(major("사학과", "인문계열", "기본설명", null));

        assertThat(context.description()).isEqualTo("역사 전공");
        assertThat(context.ragSnippets()).containsExactly("사학과 snippet");
    }

    @Test
    void matchesDecomposedHangulFileName() throws Exception {
        // macOS에서 만든 파일명은 한글이 자모 단위(NFD)로 저장될 수 있다.
        String nfd = java.text.Normalizer.normalize("major-row-174-통계학과", java.text.Normalizer.Form.NFD);
        writeMajor(nfd, "통계학과", "통계 전공");

        RecommendationCommentRequest.MajorContext context =
                service.toRecommendationMajorContext(major("통계학과", "자연계열", "기본설명", null));

        assertThat(context.description()).isEqualTo("통계 전공");
    }

    @Test
    void everyRealDatasetMajorResolvesToItsOwnFile() throws Exception {
        Path realMajors = Path.of("datasets/majors");
        ReflectionTestUtils.setField(service, "majorsPath", realMajors.toString());
        ReflectionTestUtils.setField(service, "ragPath", "datasets/rag/per-major");
        ObjectMapper objectMapper = new ObjectMapper();
        try (var files = Files.list(realMajors)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".service.json")).toList()) {
                String name = objectMapper.readTree(file.toFile()).path("majorName").asText();
                String expected = objectMapper.readTree(file.toFile()).path("description").asText();

                RecommendationCommentRequest.MajorContext context =
                        service.toRecommendationMajorContext(major(name, "기본분류", "기본설명", null));

                assertThat(context.description()).as(name).isEqualTo(expected);
            }
        }
    }

    private void writeMajor(String baseName, String majorName, String description) throws Exception {
        Files.writeString(majorsDir.resolve(baseName + ".service.json"),
                "{\"majorName\":\"" + majorName + "\",\"description\":\"" + description + "\"}");
        Files.writeString(ragDir.resolve(baseName + ".rag.jsonl"),
                "{\"content\":\"" + majorName + " snippet\"}\n");
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
