package com.jinroon.jobe.domain.exploration.consultation;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.activity.ActivityRecommendationService;
import com.jinroon.jobe.domain.exploration.service.ExplorationSessionService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Answers the result page's fixed questions from the same records shown on that page. */
@Component
@RequiredArgsConstructor
public class ConsultationGuidance {
    private final ExplorationSessionService results;
    private final ActivityRecommendationService activities;

    enum Topic {
        CANDIDATE_REASONS(ConsultationDtos.SourceKind.RESULT_SNAPSHOT, "탐색 진단 결과"),
        ACTIVITY_PURPOSE(ConsultationDtos.SourceKind.ACTIVITY_CATALOG, "검토된 추천 활동");

        final ConsultationDtos.SourceKind source;
        final String sourceTitle;
        Topic(ConsultationDtos.SourceKind source, String sourceTitle) {
            this.source = source;
            this.sourceTitle = sourceTitle;
        }
    }

    record Answer(String content, String sourceVersion) {}

    static Topic topic(String question) {
        if (question == null) return null;
        return switch (question.strip()) {
            case "이 전공군들이 왜 나왔는지 설명해줘" -> Topic.CANDIDATE_REASONS;
            case "추천 활동은 무엇을 알아보려는 거야?" -> Topic.ACTIVITY_PURPOSE;
            default -> null;
        };
    }

    Answer answer(Topic topic, long user, long resultId, String resultVersion) {
        return switch (topic) {
            case CANDIDATE_REASONS -> reasons(results.resultById(user, resultId), resultVersion);
            case ACTIVITY_PURPOSE -> activities(activities.get(user, resultId));
        };
    }

    private Answer reasons(JsonNode result, String version) {
        var groups = new ArrayList<String>();
        for (JsonNode group : result.path("groups")) {
            var major = firstVisibleMajor(group.path("majors"));
            var reasons = new ArrayList<String>();
            major.path("reasons").forEach(reason -> {
                String sentence = reason.path("text").asText();
                if (!sentence.isBlank()) reasons.add(sentence);
            });
            if (!reasons.isEmpty()) {
                String name = group.path("name").asText(major.path("majorName").asText());
                groups.add(name + "의 " + major.path("majorName").asText() + " · " + String.join(" ", reasons));
            }
        }
        if (groups.isEmpty()) {
            return new Answer("이 결과에는 응답 근거를 설명할 수 있는 전공군이 아직 없어요.", null);
        }
        return new Answer("결과에 나온 전공군별로, 대표 후보를 고를 때 살펴본 응답을 알려드릴게요.\n\n"
                + String.join("\n\n", groups), version);
    }

    private JsonNode firstVisibleMajor(JsonNode majors) {
        for (JsonNode major : majors) {
            if (!major.path("collapsed").asBoolean()) return major;
        }
        return majors.path(0);
    }

    private Answer activities(JsonNode recommendation) {
        List<String> lines = new ArrayList<>();
        if ("AVAILABLE".equals(recommendation.path("activityAvailability").asText())) {
            for (JsonNode activity : recommendation.path("nextActivities")) {
                String output = activity.path("artifacts").path(0).asText();
                String purpose = switch (activity.path("kind").asText()) {
                    case "INFORMATION" -> "전공에서 확인할 내용을 찾아 정리해요.";
                    case "SMALL_TASK" -> "전공과 관련된 일을 작게 해봐요.";
                    case "REFLECTION_AND_NEXT_STEP" -> "해본 내용을 돌아보고 다음에 확인할 점을 정해요.";
                    default -> "활동 과정을 살펴봐요.";
                };
                lines.add(activity.path("title").asText() + " · " + purpose + " 남길 결과물은 ‘" + output + "’예요.");
            }
        }
        if (lines.isEmpty()) {
            return new Answer("이 결과에서 안내할 수 있는 승인된 탐색 활동이 아직 없어요.", null);
        }
        return new Answer("추천 활동에서는 전공에서 하는 일을 작게 체험하고, 해본 결과를 남겨 관심이 이어지는지 확인해요.\n\n"
                + String.join("\n\n", lines), recommendation.path("catalogVersion").asText());
    }
}
