package com.jinroon.jobe.domain.diagnosis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.diagnosis.dto.DiagnosisProfileAdjustment;
import com.jinroon.jobe.domain.diagnosis.dto.DiagnosisProfileSnapshot;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.enums.MajorEnums.MajorDifficulty;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DiagnosisProfileScoringService {
    private static final String PROBLEM_SOLVING = "문제 해결 능력";
    private static final String CREATIVE_THINKING = "창의적 사고";
    private static final String COLLABORATION_COMMUNICATION = "협업 및 소통";

    private static final List<CareerKeywordGroup> CAREER_KEYWORD_GROUPS = List.of(
            new CareerKeywordGroup(
                    List.of("data", "ai", "artificial intelligence", "machine learning", "scientist", "analytics",
                            "데이터", "인공지능"),
                    List.of("data", "ai", "artificial intelligence", "machine learning", "statistics", "software",
                            "computer", "information", "데이터", "인공지능", "통계", "소프트웨어", "컴퓨터")),
            new CareerKeywordGroup(
                    List.of("developer", "software", "coding", "programmer", "engineer", "개발자", "소프트웨어", "코딩"),
                    List.of("software", "computer", "information", "engineering", "developer", "coding",
                            "소프트웨어", "컴퓨터", "정보", "공학")),
            new CareerKeywordGroup(
                    List.of("robot", "robotics", "로봇"),
                    List.of("robot", "robotics", "mechanical", "electrical", "computer", "로봇", "기계", "전기", "전자")),
            new CareerKeywordGroup(
                    List.of("teacher", "education", "educator", "교사", "선생님", "교육"),
                    List.of("education", "teacher", "pedagogy", "교육", "교사")),
            new CareerKeywordGroup(
                    List.of("nurse", "doctor", "medical", "healthcare", "health", "간호", "의사", "보건", "의료"),
                    List.of("nursing", "medicine", "medical", "health", "biology", "life science",
                            "간호", "의학", "보건", "의료", "생명", "생물")),
            new CareerKeywordGroup(
                    List.of("lawyer", "law", "judge", "prosecutor", "변호사", "법", "판사", "검사"),
                    List.of("law", "legal", "administration", "political", "법", "행정", "정치")),
            new CareerKeywordGroup(
                    List.of("designer", "design", "ux", "visual", "animation", "디자이너", "디자인", "애니메이션"),
                    List.of("design", "visual", "animation", "media", "art", "디자인", "시각", "애니메이션", "미디어", "예술")),
            new CareerKeywordGroup(
                    List.of("media", "broadcast", "journalist", "content", "producer", "방송", "기자", "콘텐츠", "미디어"),
                    List.of("media", "communication", "journalism", "content", "advertising", "broadcast",
                            "미디어", "커뮤니케이션", "신문", "방송", "광고", "콘텐츠")),
            new CareerKeywordGroup(
                    List.of("business", "startup", "marketing", "management", "entrepreneur", "경영", "마케팅", "창업"),
                    List.of("business", "economics", "marketing", "management", "administration", "advertising",
                            "경영", "경제", "마케팅", "행정", "광고"))
    );

    private final ObjectMapper objectMapper;

    public DiagnosisProfileSnapshot parse(String inputSnapshot) {
        return DiagnosisProfileSnapshot.fromJson(inputSnapshot, objectMapper);
    }

    public float calculateProfileBonus(DiagnosisSession session, Major major) {
        if (session == null) {
            return 0.0f;
        }
        return calculateProfileAdjustment(session, major).bonus();
    }

    public float calculateProfileBonus(DiagnosisProfileSnapshot profile, Major major) {
        return calculateProfileAdjustment(profile, major).bonus();
    }

    public DiagnosisProfileAdjustment calculateProfileAdjustment(DiagnosisSession session, Major major) {
        if (session == null) {
            return DiagnosisProfileAdjustment.neutral();
        }
        return calculateProfileAdjustment(parse(session.getInputSnapshot()), major);
    }

    public DiagnosisProfileAdjustment calculateProfileAdjustment(DiagnosisProfileSnapshot profile, Major major) {
        if (profile == null || major == null || !profile.hasProfileSignal()) {
            return DiagnosisProfileAdjustment.neutral();
        }

        float subjectBonus = subjectSignal(profile.selectedSubjects(), major) * 1.8f;
        float learningStyleBonus = learningStyleSignal(profile.learningStyle(), major) * 0.9f;
        float exploreSpectrumBonus = exploreSpectrumSignal(profile.exploreSpectrum(), major) * 0.8f;
        float selfScoreBonus = selfScoreSignal(profile, major) * 1.5f;
        float goalTextBonus = goalTextSignal(profile, major) * 1.2f;
        float studyLoadBonus = studyLoadSignal(profile.studyHours(), major) * 0.8f;

        List<String> reasons = new ArrayList<>();
        addSubjectReason(reasons, subjectBonus, profile.selectedSubjects());
        addLearningStyleReason(reasons, learningStyleBonus, profile.learningStyle());
        addExploreSpectrumReason(reasons, exploreSpectrumBonus, profile.exploreSpectrum());
        addSelfScoreReason(reasons, selfScoreBonus, profile);
        addGoalTextReason(reasons, goalTextBonus);
        addStudyLoadReason(reasons, studyLoadBonus);

        float bonus = roundOneDecimal(clamp(
                subjectBonus + learningStyleBonus + exploreSpectrumBonus + selfScoreBonus + goalTextBonus
                        + studyLoadBonus,
                -5.0f,
                5.0f
        ));
        return new DiagnosisProfileAdjustment(bonus, reasons);
    }

    public float adjustedFinalScore(Float competencyScore, Float tendencyScore, Float providedFinalScore,
                                    float profileBonus) {
        float baseScore;
        if (competencyScore != null && tendencyScore != null) {
            baseScore = competencyScore * 0.6f + tendencyScore * 0.4f;
        } else {
            baseScore = providedFinalScore == null ? 0.0f : providedFinalScore;
        }
        return roundOneDecimal(clamp(baseScore + profileBonus, 0.0f, 100.0f));
    }

    private float subjectSignal(List<String> selectedSubjects, Major major) {
        if (selectedSubjects == null || selectedSubjects.isEmpty()) {
            return 0.0f;
        }

        float total = 0.0f;
        for (String subject : selectedSubjects) {
            total += subjectSignal(subject, major);
        }
        return clamp(total / selectedSubjects.size(), -1.0f, 1.0f);
    }

    private float subjectSignal(String subject, Major major) {
        if (subject == null || subject.isBlank()) {
            return 0.0f;
        }

        if (containsAny(subject, "수학")) {
            return bestKeywordOrAxisSignal(major,
                    axisAverage(major.getReqMathLogic(), major.getTendLogicalInquiry(), major.getTendDataAnalytics()),
                    List.of("수학", "통계", "데이터", "컴퓨터", "소프트웨어", "공학"));
        }
        if (containsAny(subject, "정보", "코딩")) {
            return bestKeywordOrAxisSignal(major,
                    axisAverage(major.getReqInfoTech(), major.getReqImplementation(), major.getTendPracticalTech()),
                    List.of("컴퓨터", "소프트웨어", "정보", "데이터", "ai", "인공지능", "공학"));
        }
        if (containsAny(subject, "과학")) {
            return bestKeywordOrAxisSignal(major,
                    axisAverage(major.getTendLifeHealth(), major.getReqSystemUnderstanding(), major.getReqDataAnalysis()),
                    List.of("과학", "생명", "화학", "물리", "환경", "의학", "보건", "간호", "공학"));
        }
        if (containsAny(subject, "예술", "미술", "음악")) {
            return bestKeywordOrAxisSignal(major,
                    axisAverage(major.getTendArtCreative()),
                    List.of("예술", "디자인", "미술", "음악", "영상", "애니", "패션", "무용"));
        }
        if (containsAny(subject, "국어", "영어", "사회")) {
            return bestKeywordOrAxisSignal(major,
                    axisAverage(major.getReqCommunication(), major.getTendSocialCooperation(),
                            major.getTendEducationGuide()),
                    List.of("인문", "사회", "교육", "문학", "언어", "영어", "국어", "경영", "행정", "법", "미디어",
                            "커뮤니케이션"));
        }
        return 0.0f;
    }

    private float learningStyleSignal(String learningStyle, Major major) {
        if (learningStyle == null || learningStyle.isBlank()) {
            return 0.0f;
        }

        if ("theory".equalsIgnoreCase(learningStyle)) {
            return clamp(axisSignal(axisAverage(major.getTendTheoryAcademic(), major.getTendLogicalInquiry())),
                    -0.8f, 1.0f);
        }
        if ("practice".equalsIgnoreCase(learningStyle)) {
            return clamp(axisSignal(axisAverage(major.getTendPracticalTech(), major.getReqImplementation(),
                    major.getTendSystemOperation())), -0.8f, 1.0f);
        }
        return 0.0f;
    }

    private float exploreSpectrumSignal(Integer exploreSpectrum, Major major) {
        if (exploreSpectrum == null) {
            return 0.0f;
        }

        if (exploreSpectrum >= 65) {
            float emerging = containsAny(majorText(major), "ai", "인공지능", "데이터", "로봇", "소프트웨어", "신소재",
                    "반도체", "항공", "미디어", "융합")
                    ? 1.0f
                    : axisSignal(axisAverage(major.getReqInfoTech(), major.getTendDataAnalytics(),
                            major.getTendPracticalTech()));
            return emerging * ((exploreSpectrum - 50) / 50.0f);
        }
        if (exploreSpectrum <= 35) {
            float stable = containsAny(majorText(major), "간호", "교육", "행정", "회계", "세무", "법", "의학", "약학",
                    "치위생", "물리치료")
                    || major.getDifficulty() == MajorDifficulty.low
                    || major.getDifficulty() == MajorDifficulty.mid
                    ? 1.0f
                    : -0.4f;
            return stable * ((50 - exploreSpectrum) / 50.0f);
        }
        return 0.0f;
    }

    private float selfScoreSignal(DiagnosisProfileSnapshot profile, Major major) {
        float problemSolving = neutralizedSelfScore(profile.scoreOrNeutral(PROBLEM_SOLVING))
                * axisSignal(axisAverage(major.getReqProblemSolving(), major.getReqMathLogic(),
                major.getReqDataAnalysis(), major.getReqInfoTech()));
        float creativeThinking = neutralizedSelfScore(profile.scoreOrNeutral(CREATIVE_THINKING))
                * bestKeywordOrAxisSignal(major, axisAverage(major.getTendArtCreative()),
                List.of("디자인", "예술", "미디어", "영상", "콘텐츠", "애니", "창작"));
        float collaboration = neutralizedSelfScore(profile.scoreOrNeutral(COLLABORATION_COMMUNICATION))
                * axisSignal(axisAverage(major.getReqCommunication(), major.getReqCollaboration(),
                major.getTendSocialCooperation(), major.getTendEducationGuide()));

        return clamp((problemSolving + creativeThinking + collaboration) / 3.0f, -1.0f, 1.0f);
    }

    private float goalTextSignal(DiagnosisProfileSnapshot profile, Major major) {
        if (profile == null || major == null) {
            return 0.0f;
        }
        String text = majorText(major);
        float dreamJobSignal = careerTextSignal(profile.dreamJob(), text);
        float aspirationSignal = careerTextSignal(profile.aspiration(), text) * 0.75f;
        return clamp(Math.max(dreamJobSignal, aspirationSignal), 0.0f, 1.0f);
    }

    private float studyLoadSignal(Double studyHours, Major major) {
        if (studyHours == null || major == null) {
            return 0.0f;
        }

        double hours = clamp(studyHours.floatValue(), 0.0f, 24.0f);
        boolean highDifficulty = major.getDifficulty() == MajorDifficulty.high;
        boolean demanding = isDemandingMajor(major);

        if (hours >= 5.0d && highDifficulty) {
            return 0.75f;
        }
        if (hours <= 1.0d && demanding) {
            return -1.0f;
        }
        if (hours <= 1.5d && highDifficulty) {
            return -0.75f;
        }
        return 0.0f;
    }

    private boolean isDemandingMajor(Major major) {
        if (major == null) {
            return false;
        }

        String text = majorText(major);
        boolean demandingText = containsAny(text,
                "medicine", "medical", "nursing", "pharmacy", "software", "computer", "data", "ai",
                "artificial intelligence", "machine learning", "robotics", "engineering", "aerospace",
                "electrical", "electronic");
        float technicalLoad = axisAverage(
                major.getReqMathLogic(),
                major.getReqProblemSolving(),
                major.getReqInfoTech(),
                major.getReqImplementation(),
                major.getReqSystemUnderstanding(),
                major.getReqDataAnalysis()
        );
        float healthLoad = axisAverage(major.getTendLifeHealth(), major.getReqSystemUnderstanding());
        return demandingText || technicalLoad >= 75.0f || healthLoad >= 75.0f;
    }

    private float careerTextSignal(String userText, String majorText) {
        if (userText == null || userText.isBlank() || majorText == null || majorText.isBlank()) {
            return 0.0f;
        }
        String normalizedUserText = normalizeText(userText);
        String normalizedMajorText = normalizeText(majorText);
        return Math.max(
                groupedCareerKeywordMatch(normalizedUserText, normalizedMajorText),
                directKeywordMatch(normalizedUserText, normalizedMajorText)
        );
    }

    private float groupedCareerKeywordMatch(String userText, String majorText) {
        for (CareerKeywordGroup group : CAREER_KEYWORD_GROUPS) {
            if (containsAny(userText, group.goalKeywords().toArray(String[]::new))
                    && containsAny(majorText, group.majorKeywords().toArray(String[]::new))) {
                return 1.0f;
            }
        }
        return 0.0f;
    }

    private float directKeywordMatch(String userText, String majorText) {
        for (String token : userText.split("\\s+")) {
            if (isMeaningfulGoalToken(token) && majorText.contains(token)) {
                return 0.6f;
            }
        }
        return 0.0f;
    }

    private void addSubjectReason(List<String> reasons, float subjectBonus, List<String> selectedSubjects) {
        if (Math.abs(subjectBonus) < 0.2f || selectedSubjects == null || selectedSubjects.isEmpty()) {
            return;
        }
        if (subjectBonus > 0) {
            reasons.add("선택한 관심 과목이 이 전공의 핵심 학습 영역과 잘 맞습니다.");
        } else {
            reasons.add("선택한 관심 과목과 이 전공의 핵심 학습 영역은 일부 차이가 있습니다.");
        }
    }

    private void addLearningStyleReason(List<String> reasons, float learningStyleBonus, String learningStyle) {
        if (Math.abs(learningStyleBonus) < 0.2f || learningStyle == null || learningStyle.isBlank()) {
            return;
        }
        if ("practice".equalsIgnoreCase(learningStyle) && learningStyleBonus > 0) {
            reasons.add("실습 중심 학습 성향이 이 전공의 적용형 학습 방식과 어울립니다.");
        } else if ("theory".equalsIgnoreCase(learningStyle) && learningStyleBonus > 0) {
            reasons.add("이론 중심 학습 성향이 이 전공의 탐구형 학습 방식과 어울립니다.");
        }
    }

    private void addExploreSpectrumReason(List<String> reasons, float exploreSpectrumBonus, Integer exploreSpectrum) {
        if (Math.abs(exploreSpectrumBonus) < 0.2f || exploreSpectrum == null) {
            return;
        }
        if (exploreSpectrum >= 65 && exploreSpectrumBonus > 0) {
            reasons.add("새로운 분야를 탐색하려는 성향이 융합/기술 변화가 큰 전공과 맞습니다.");
        } else if (exploreSpectrum <= 35 && exploreSpectrumBonus > 0) {
            reasons.add("안정적인 진로 선호가 구조화된 전공 경로와 맞습니다.");
        }
    }

    private void addSelfScoreReason(List<String> reasons, float selfScoreBonus, DiagnosisProfileSnapshot profile) {
        if (Math.abs(selfScoreBonus) < 0.2f || profile.scores().isEmpty()) {
            return;
        }
        if (selfScoreBonus > 0) {
            reasons.add("자기평가 강점이 이 전공에서 요구하는 역량과 연결됩니다.");
        } else {
            reasons.add("자기평가 결과상 이 전공의 일부 요구 역량은 보완이 필요할 수 있습니다.");
        }
    }

    private void addGoalTextReason(List<String> reasons, float goalTextBonus) {
        if (goalTextBonus < 0.2f) {
            return;
        }
        reasons.add("Career goal keywords partially match this major path.");
    }

    private void addStudyLoadReason(List<String> reasons, float studyLoadBonus) {
        if (Math.abs(studyLoadBonus) < 0.2f) {
            return;
        }
        if (studyLoadBonus > 0) {
            reasons.add("Current study time fits the preparation load of this major.");
        } else {
            reasons.add("This major may require a gradual study-load plan based on current study time.");
        }
    }

    private float bestKeywordOrAxisSignal(Major major, float axisAverage, List<String> keywords) {
        float axisSignal = axisSignal(axisAverage);
        if (containsAny(majorText(major), keywords.toArray(String[]::new))) {
            return Math.max(axisSignal, 0.9f);
        }
        return axisSignal < -0.2f ? -0.35f : axisSignal;
    }

    private static float neutralizedSelfScore(int score) {
        return clamp((score - 3) / 2.0f, -1.0f, 1.0f);
    }

    private static float axisAverage(Float... values) {
        float total = 0.0f;
        int count = 0;
        for (Float value : values) {
            if (value != null) {
                total += value;
                count++;
            }
        }
        return count == 0 ? 50.0f : total / count;
    }

    private static float axisSignal(float value) {
        return clamp((value - 50.0f) / 50.0f, -1.0f, 1.0f);
    }

    private static String majorText(Major major) {
        return String.join(" ",
                nullToEmpty(major.getName()),
                nullToEmpty(major.getCategory()),
                nullToEmpty(major.getDescription()),
                nullToEmpty(major.getCareerPaths())
        ).toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lowerText = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && lowerText.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\s가-힣]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isMeaningfulGoalToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String trimmed = token.trim();
        if (trimmed.length() < 3) {
            return false;
        }
        return !List.of("and", "the", "for", "with", "that", "this", "want", "become").contains(trimmed);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float roundOneDecimal(float value) {
        return Math.round(value * 10.0f) / 10.0f;
    }

    private record CareerKeywordGroup(List<String> goalKeywords, List<String> majorKeywords) {
    }
}
