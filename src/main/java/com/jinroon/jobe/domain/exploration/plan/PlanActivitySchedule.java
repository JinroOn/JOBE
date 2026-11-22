package com.jinroon.jobe.domain.exploration.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Formats the approved activity snapshot without asking the provider to invent tasks. */
final class PlanActivitySchedule {
    private record Week(String title, List<String> tasks, String output) {}

    static boolean apply(ArrayNode weeks, JsonNode basis) {
        if (basis.isMissingNode()) return false;
        var activities=basis.path("activities");
        var information=activity(activities,"INFORMATION");
        var practice=activity(activities,"SMALL_TASK");
        var reflection=activity(activities,"REFLECTION_AND_NEXT_STEP");
        boolean applied=false;
        for (JsonNode raw : weeks) {
            var week=(ObjectNode)raw;
            int number=week.path("week").asInt();
            Week tailored=switch(number) {
                case 3 -> information==null?null:week("확인할 주제 고르기 · ",information,artifact(information),
                        "활동 자료에서 더 알아보고 싶은 주제를 한 가지 골라 이유를 적어요.",body(information));
                case 4 -> information==null?null:week("질문 정리하기 · ",information,"전공에 관해 더 알아볼 질문 한 문장",
                        "3주차에 고른 주제를 돌아보고 자료를 읽으면서 생긴 질문을 한 문장으로 써요.");
                case 5 -> practice==null?null:week("활동 상황 이해하기 · ",practice,"주어진 상황과 해결할 일을 적은 두 줄",
                        "아래 상황을 읽고 주어진 정보와 해야 할 일을 한 줄씩 적어봐요.",body(practice));
                case 6 -> practice==null?null:week("진행 순서 정리하기 · ",practice,"활동 순서를 적은 메모",
                        "활동 순서를 읽고 각 단계에서 무엇을 확인할지 표시해요.",steps(practice));
                case 7 -> practice==null?null:week("결과물 예시 읽기 · ",practice,"따라 해볼 부분 하나를 적은 메모",
                        "결과물 예시에서 실제로 기록해야 할 항목 하나를 찾아요.","결과물 예시 · " + text(practice,"example_output"));
                case 8 -> practice==null?null:week("쉬운 방식으로 연습하기 · ",practice,"쉬운 방식으로 작성한 초안",
                        "부담이 적은 방식으로 예시를 한 번 시도하고 막힌 부분을 적어요.","쉬운 대안 · " + text(practice,"alternative"));
                case 9 -> practice==null?null:week("직접 해보기 · ",practice,artifact(practice),body(practice),steps(practice));
                case 10 -> practice==null?null:week("내 결과 점검하기 · ",practice,"비교하거나 고쳐 적은 한 가지",
                        "9주차 결과를 예시와 비교하고 설명을 한 가지 보완해요.","비교할 예시 · " + text(practice,"example_output"));
                case 11 -> reflection==null?null:week("해본 일 돌아보기 · ",reflection,artifact(reflection),body(reflection),steps(reflection));
                case 12 -> reflection==null?null:week("다음 탐색 정하기 · ",reflection,"다음에 해볼 일과 이유 한 문장",
                        "이 활동에서 흥미로웠던 점과 어려웠던 점을 떠올리고, 이어서 확인할 일을 하나 골라요.",
                        "쉬운 대안 · " + text(reflection,"alternative"));
                default -> null;
            };
            if (tailored!=null) {
                week.put("title",tailored.title()).put("expectedOutput",tailored.output());
                var tasks=week.putArray("tasks");
                tailored.tasks().forEach(tasks::add);
                applied=true;
            } else if (number>=5) {
                week.put("title","공통 탐색 연습 · " + week.path("title").asText());
            }
        }
        return applied;
    }

    private static JsonNode activity(JsonNode cards, String kind) {
        for (JsonNode card : cards) {
            if (kind.equals(card.path("kind").asText())) return card;
        }
        return null;
    }

    private static Week week(String heading, JsonNode card, String output, String... tasks) {
        List<String> content=new ArrayList<>();
        var duration=card.path("duration_minutes_proposal");
        if (duration.path("max").isIntegralNumber()) {
            content.add("전체 활동 기준 약 " + duration.path("min").asInt() + "–" + duration.path("max").asInt() + "분이에요.");
        }
        List<String> materials=new ArrayList<>();
        card.path("requirements").path("materials").forEach(item->materials.add(item.asText()));
        if (!materials.isEmpty()) content.add("준비물 · " + String.join(", ",materials));
        for (String task : tasks) if (!task.isBlank()) content.add(task);
        return new Week(heading + text(card,"title"),content,output);
    }

    private static String artifact(JsonNode activity) {
        return activity.path("artifacts").path(0).asText();
    }

    private static String text(JsonNode activity, String field) {
        return activity.path(field).asText();
    }

    private static String steps(JsonNode activity) {
        List<String> lines=new ArrayList<>();
        activity.path("steps").forEach(item->lines.add((lines.size()+1)+". "+item.asText()));
        return String.join(" ",lines);
    }

    private static String body(JsonNode activity) {
        return text(activity,"body_markdown").lines().map(String::strip)
                .filter(line->!line.isBlank()&&!line.startsWith("#")&&!line.startsWith("```"))
                .collect(Collectors.joining(" "));
    }
}
