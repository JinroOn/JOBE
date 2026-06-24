package com.jinroon.jobe.domain.diagnosis.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.diagnosis.dto.DiagnosisProfileAdjustment;
import com.jinroon.jobe.domain.diagnosis.dto.DiagnosisProfileSnapshot;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.enums.MajorEnums.MajorDifficulty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DiagnosisProfileScoringServiceTest {

    private DiagnosisProfileScoringService service;

    @BeforeEach
    void setUp() {
        service = new DiagnosisProfileScoringService(new ObjectMapper());
    }

    @Test
    void parsesFullValidInputSnapshot() {
        String json = """
                {
                  "grade": "1학년",
                  "dreamJob": "AI 데이터 사이언티스트",
                  "selectedSubjects": ["수학", "과학", "정보/코딩"],
                  "studyHours": 4.5,
                  "learningStyle": "theory",
                  "exploreSpectrum": 70,
                  "scores": {
                    "문제 해결 능력": 4,
                    "창의적 사고": 3,
                    "협업 및 소통": 5
                  },
                  "aspiration": "데이터로 사회문제를 해결하고 싶다"
                }
                """;

        DiagnosisProfileSnapshot profile = service.parse(json);

        assertThat(profile.grade()).isEqualTo("1학년");
        assertThat(profile.dreamJob()).isEqualTo("AI 데이터 사이언티스트");
        assertThat(profile.selectedSubjects()).containsExactly("수학", "과학", "정보/코딩");
        assertThat(profile.studyHours()).isEqualTo(4.5);
        assertThat(profile.learningStyle()).isEqualTo("theory");
        assertThat(profile.exploreSpectrum()).isEqualTo(70);
        assertThat(profile.scoreOrNeutral("문제 해결 능력")).isEqualTo(4);
        assertThat(profile.aspiration()).isEqualTo("데이터로 사회문제를 해결하고 싶다");
    }

    @Test
    void malformedOrMissingInputSnapshotIsNeutral() {
        DiagnosisProfileSnapshot malformed = service.parse("{not-json");
        DiagnosisProfileSnapshot missing = service.parse(null);
        Major major = softwareMajor();

        assertThat(malformed.hasProfileSignal()).isFalse();
        assertThat(missing.hasProfileSignal()).isFalse();
        assertThat(service.calculateProfileBonus(malformed, major)).isZero();
        assertThat(service.calculateProfileBonus(missing, major)).isZero();
    }

    @Test
    void subjectBasedAdjustmentFavorsRelatedMajor() {
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "selectedSubjects": ["정보/코딩", "수학"],
                  "scores": {}
                }
                """);

        float bonus = service.calculateProfileBonus(profile, softwareMajor());

        assertThat(bonus).isPositive();
    }

    @Test
    void profileAdjustmentIncludesStableReasons() {
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "selectedSubjects": ["정보/코딩", "수학"],
                  "learningStyle": "practice",
                  "exploreSpectrum": 80,
                  "scores": {
                    "문제 해결 능력": 5
                  }
                }
                """);

        DiagnosisProfileAdjustment adjustment = service.calculateProfileAdjustment(profile, softwareMajor());

        assertThat(adjustment.bonus()).isBetween(-5.0f, 5.0f);
        assertThat(adjustment.reasons()).isNotEmpty();
        assertThat(adjustment.conciseReason()).contains("관심 과목");
    }

    @Test
    void learningStyleAdjustmentFavorsMatchingMajor() {
        DiagnosisProfileSnapshot theoryProfile = service.parse("""
                {
                  "learningStyle": "theory"
                }
                """);
        Major theoryMajor = major("수학과", "자연과학", MajorDifficulty.mid);
        ReflectionTestUtils.setField(theoryMajor, "tendTheoryAcademic", 90.0f);
        ReflectionTestUtils.setField(theoryMajor, "tendLogicalInquiry", 85.0f);

        float bonus = service.calculateProfileBonus(theoryProfile, theoryMajor);

        assertThat(bonus).isPositive();
    }

    @Test
    void selfScoreAdjustmentUsesRelevantMajorAxes() {
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "scores": {
                    "문제 해결 능력": 5,
                    "창의적 사고": 3,
                    "협업 및 소통": 3
                  }
                }
                """);

        float bonus = service.calculateProfileBonus(profile, softwareMajor());

        assertThat(bonus).isPositive();
    }

    @Test
    void dreamJobDirectMatchIncreasesBonusForRelatedMajor() {
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "dreamJob": "data scientist"
                }
                """);

        DiagnosisProfileAdjustment adjustment = service.calculateProfileAdjustment(profile, dataScienceMajor());

        assertThat(adjustment.bonus()).isPositive();
        assertThat(adjustment.reasons()).contains("Career goal keywords partially match this major path.");
    }

    @Test
    void aspirationKeywordMatchIncreasesBonusForRelatedMajor() {
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "aspiration": "I want to build machine learning products from data."
                }
                """);

        float bonus = service.calculateProfileBonus(profile, dataScienceMajor());

        assertThat(bonus).isPositive();
    }

    @Test
    void unrelatedGoalTextRemainsNeutral() {
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "dreamJob": "chef",
                  "aspiration": "I want to open a bakery."
                }
                """);

        DiagnosisProfileAdjustment adjustment = service.calculateProfileAdjustment(profile, dataScienceMajor());

        assertThat(adjustment.bonus()).isZero();
        assertThat(adjustment.reasons()).isEmpty();
    }

    @Test
    void goalTextDoesNotPushProfileBonusOverUpperBound() {
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "dreamJob": "AI data scientist",
                  "selectedSubjects": ["?뺣낫/肄붾뵫", "?섑븰", "怨쇳븰"],
                  "learningStyle": "practice",
                  "exploreSpectrum": 100,
                  "scores": {
                    "臾몄젣 ?닿껐 ?λ젰": 5,
                    "李쎌쓽???ш퀬": 5,
                    "?묒뾽 諛??뚰넻": 5
                  }
                }
                """);

        DiagnosisProfileAdjustment adjustment = service.calculateProfileAdjustment(profile, dataScienceMajor());

        assertThat(adjustment.bonus()).isBetween(-5.0f, 5.0f);
    }

    @Test
    void goalReasonDoesNotIncludeRawAspirationText() {
        String rawAspiration = "I want to solve data problems for vulnerable students with machine learning.";
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "aspiration": "I want to solve data problems for vulnerable students with machine learning."
                }
                """);

        DiagnosisProfileAdjustment adjustment = service.calculateProfileAdjustment(profile, dataScienceMajor());

        assertThat(adjustment.reasons()).isNotEmpty();
        assertThat(String.join(" ", adjustment.reasons())).doesNotContain(rawAspiration);
    }

    @Test
    void highStudyHoursGivesSmallPositiveBonusForHighDifficultyMajor() {
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "studyHours": 5.5
                }
                """);

        DiagnosisProfileAdjustment adjustment = service.calculateProfileAdjustment(profile, dataScienceMajor());

        assertThat(adjustment.bonus()).isBetween(0.1f, 1.0f);
        assertThat(adjustment.reasons())
                .contains("Current study time fits the preparation load of this major.");
    }

    @Test
    void lowStudyHoursGivesSmallCautionForHighDifficultyMajor() {
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "studyHours": 1.2
                }
                """);
        Major highDifficultyMajor = major("Advanced Mathematics", "Science", MajorDifficulty.high);

        DiagnosisProfileAdjustment adjustment = service.calculateProfileAdjustment(profile, highDifficultyMajor);

        assertThat(adjustment.bonus()).isBetween(-1.0f, -0.1f);
        assertThat(adjustment.reasons())
                .contains("This major may require a gradual study-load plan based on current study time.");
    }

    @Test
    void veryLowStudyHoursGivesOnlySmallCautionForDemandingMajor() {
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "studyHours": 0.8
                }
                """);

        float bonus = service.calculateProfileBonus(profile, dataScienceMajor());

        assertThat(bonus).isBetween(-1.0f, -0.1f);
    }

    @Test
    void lowStudyHoursDoesNotStronglyPenalizeLowDifficultyMajor() {
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "studyHours": 0.8
                }
                """);

        DiagnosisProfileAdjustment adjustment = service.calculateProfileAdjustment(profile, lowDifficultyMajor());

        assertThat(adjustment.bonus()).isZero();
        assertThat(adjustment.reasons()).isEmpty();
    }

    @Test
    void missingStudyHoursRemainsNeutralWhenNoOtherProfileSignalsExist() {
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "studyHours": null
                }
                """);

        assertThat(service.calculateProfileBonus(profile, dataScienceMajor())).isZero();
    }

    @Test
    void studyLoadDoesNotPushProfileBonusOutsideBounds() {
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "studyHours": 6.0,
                  "dreamJob": "AI data scientist",
                  "selectedSubjects": ["?뺣낫/肄붾뵫", "?섑븰", "怨쇳븰"],
                  "learningStyle": "practice",
                  "exploreSpectrum": 100,
                  "scores": {
                    "臾몄젣 ?닿껐 ?λ젰": 5,
                    "李쎌쓽???ш퀬": 5,
                    "?묒뾽 諛??뚰넻": 5
                  }
                }
                """);

        DiagnosisProfileAdjustment adjustment = service.calculateProfileAdjustment(profile, dataScienceMajor());

        assertThat(adjustment.bonus()).isBetween(-5.0f, 5.0f);
    }

    @Test
    void studyLoadReasonIsConciseAndNotDiscouraging() {
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "studyHours": 0.8
                }
                """);

        DiagnosisProfileAdjustment adjustment = service.calculateProfileAdjustment(profile, dataScienceMajor());
        String reasons = String.join(" ", adjustment.reasons()).toLowerCase();

        assertThat(reasons).doesNotContain("too hard");
        assertThat(reasons).doesNotContain("cannot");
        assertThat(reasons).contains("gradual study-load plan");
    }

    @Test
    void adjustedFinalScoreStaysWithinRange() {
        assertThat(service.adjustedFinalScore(100.0f, 100.0f, 100.0f, 5.0f)).isEqualTo(100.0f);
        assertThat(service.adjustedFinalScore(0.0f, 0.0f, 0.0f, -5.0f)).isEqualTo(0.0f);
    }

    @Test
    void profileBonusStaysBounded() {
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "selectedSubjects": ["정보/코딩", "수학", "과학"],
                  "learningStyle": "practice",
                  "exploreSpectrum": 100,
                  "scores": {
                    "문제 해결 능력": 5,
                    "창의적 사고": 5,
                    "협업 및 소통": 5
                  }
                }
                """);

        DiagnosisProfileAdjustment adjustment = service.calculateProfileAdjustment(profile, softwareMajor());

        assertThat(adjustment.bonus()).isBetween(-5.0f, 5.0f);
    }

    @Test
    void oldSessionWithoutProfileFieldsStillGeneratesNeutralBonus() {
        DiagnosisProfileSnapshot profile = service.parse("""
                {
                  "tendencyVector": {"logicalInquiry": 40}
                }
                """);

        assertThat(service.calculateProfileBonus(profile, softwareMajor())).isZero();
        assertThat(service.calculateProfileAdjustment(profile, softwareMajor()).reasons()).isEmpty();
    }

    private Major softwareMajor() {
        Major major = major("컴퓨터공학과", "공학", MajorDifficulty.high);
        ReflectionTestUtils.setField(major, "description", "소프트웨어와 인공지능, 데이터 시스템을 다룬다.");
        ReflectionTestUtils.setField(major, "careerPaths", "소프트웨어 개발자, 데이터 엔지니어");
        ReflectionTestUtils.setField(major, "reqMathLogic", 85.0f);
        ReflectionTestUtils.setField(major, "reqProblemSolving", 90.0f);
        ReflectionTestUtils.setField(major, "reqInfoTech", 95.0f);
        ReflectionTestUtils.setField(major, "reqImplementation", 90.0f);
        ReflectionTestUtils.setField(major, "reqDataAnalysis", 80.0f);
        ReflectionTestUtils.setField(major, "tendLogicalInquiry", 80.0f);
        ReflectionTestUtils.setField(major, "tendPracticalTech", 90.0f);
        ReflectionTestUtils.setField(major, "tendDataAnalytics", 85.0f);
        return major;
    }

    private Major dataScienceMajor() {
        Major major = major("Data Science", "Engineering", MajorDifficulty.high);
        ReflectionTestUtils.setField(major, "description", "Statistics, software, artificial intelligence, and machine learning.");
        ReflectionTestUtils.setField(major, "careerPaths", "Data scientist, machine learning engineer, analytics engineer.");
        ReflectionTestUtils.setField(major, "reqMathLogic", 85.0f);
        ReflectionTestUtils.setField(major, "reqProblemSolving", 90.0f);
        ReflectionTestUtils.setField(major, "reqInfoTech", 90.0f);
        ReflectionTestUtils.setField(major, "reqImplementation", 80.0f);
        ReflectionTestUtils.setField(major, "reqDataAnalysis", 95.0f);
        ReflectionTestUtils.setField(major, "tendLogicalInquiry", 85.0f);
        ReflectionTestUtils.setField(major, "tendPracticalTech", 80.0f);
        ReflectionTestUtils.setField(major, "tendDataAnalytics", 95.0f);
        return major;
    }

    private Major lowDifficultyMajor() {
        Major major = major("General Studies", "Liberal Arts", MajorDifficulty.low);
        ReflectionTestUtils.setField(major, "description", "Broad introductory study path.");
        ReflectionTestUtils.setField(major, "careerPaths", "Coordinator, assistant, generalist.");
        ReflectionTestUtils.setField(major, "reqMathLogic", 30.0f);
        ReflectionTestUtils.setField(major, "reqProblemSolving", 35.0f);
        ReflectionTestUtils.setField(major, "reqInfoTech", 20.0f);
        ReflectionTestUtils.setField(major, "reqImplementation", 20.0f);
        ReflectionTestUtils.setField(major, "reqDataAnalysis", 25.0f);
        ReflectionTestUtils.setField(major, "tendLifeHealth", 20.0f);
        return major;
    }

    private Major major(String name, String category, MajorDifficulty difficulty) {
        Major major = newEntity(Major.class);
        ReflectionTestUtils.setField(major, "name", name);
        ReflectionTestUtils.setField(major, "category", category);
        ReflectionTestUtils.setField(major, "difficulty", difficulty);
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
