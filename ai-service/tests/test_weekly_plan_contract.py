from __future__ import annotations

import asyncio
import re

from app.chain_plan import GeneratedWeeklyPlan, GeneratedWeeklyPlanItem, WeeklyPlanChain
from app.models import WeeklyPlanConstraints, WeeklyPlanRequest, WeeklyPlanTargetMajor
from app.models import Profile as PlanProfile

BANNED_WORDS = ("반드시", "무조건")
INTERNAL_TERMS = (
    "RAG",
    "rag",
    "rag 데이터",
    "snippet",
    "스니펫",
    "검색 조각",
    "내부 데이터",
    "프롬프트",
    "majorContext",
    "ragSnippets",
)


def build_request(*, weeks: int) -> WeeklyPlanRequest:
    return WeeklyPlanRequest(
        sessionId=3001,
        targetMajor=WeeklyPlanTargetMajor(majorName="컴퓨터공학", fitScore=87.2),
        weaknessFocus=["communicationScore", "systemUnderstandingScore"],
        profile=PlanProfile(
            mathLogicalScore=73,
            problemSolvingScore=81,
            infoTechUtilizationScore=79,
            softwareImplementationScore=85,
            systemUnderstandingScore=61,
            dataAnalysisScore=67,
            communicationScore=58,
            collaborationScore=64,
            selfManagementScore=72,
        ),
        constraints=WeeklyPlanConstraints(
            weeks=weeks,
            studyHoursPerWeek=8,
            preferredStyle="practice-first",
        ),
    )


def test_weekly_plan_system_prompt_treats_braces_as_literal_text() -> None:
    chain = WeeklyPlanChain()
    chain.prompt_text = 'Use "{week}" only as an example, not as a template variable.'

    messages = chain._build_prompt().format_messages(input_json='{"weeks": 4}')  # noqa: SLF001

    assert messages[0].content == 'Use "{week}" only as an example, not as a template variable.'
    assert messages[1].content == 'Input JSON:\n{"weeks": 4}'


def sentence_count(text: str) -> int:
    return len([s for s in re.split(r"(?<=[.!?])\s+", text.strip()) if s.strip()])


def assert_plan_quality(response, expected_weeks: int) -> None:
    assert response.overview
    assert 3 <= sentence_count(response.overview) <= 5
    assert len(response.weeklyPlan) == expected_weeks
    assert len(response.riskNotes) <= 3
    assert not any(term in response.overview for term in INTERNAL_TERMS)
    assert all(not any(term in note for term in INTERNAL_TERMS) for note in response.riskNotes)

    for item in response.weeklyPlan:
        assert item.goal
        assert 2 <= len(item.tasks) <= 4
        assert 1 <= len(item.recommendedResources) <= 3
        assert item.checkpoint

        assert not any(b in item.goal for b in BANNED_WORDS)
        assert all(not any(b in task for b in BANNED_WORDS) for task in item.tasks)
        assert all(not any(b in res for b in BANNED_WORDS) for res in item.recommendedResources)
        assert not any(term in item.goal for term in INTERNAL_TERMS)
        assert all(not any(term in task for term in INTERNAL_TERMS) for task in item.tasks)
        assert all(not any(term in res for term in INTERNAL_TERMS) for res in item.recommendedResources)
        assert not any(term in item.checkpoint for term in INTERNAL_TERMS)


def test_weekly_plan_mock_generation_6_weeks() -> None:
    chain = WeeklyPlanChain()
    request = build_request(weeks=6)
    response = chain._mock_response(request=request, request_id="plan-mock-6")  # noqa: SLF001
    assert_plan_quality(response, expected_weeks=6)


def test_generate_with_meta_falls_back_when_llm_provider_is_unavailable() -> None:
    chain = WeeklyPlanChain()
    chain.use_mock = False
    chain.provider = "unsupported"
    request = build_request(weeks=6)

    result = asyncio.run(chain.generate_with_meta(request=request, request_id="plan-provider-fallback"))

    assert result.fallback_reasons == ["unsupported_provider_fallback"]
    assert_plan_quality(result.response, expected_weeks=6)


def test_weekly_plan_boundary_4_and_12_weeks() -> None:
    chain = WeeklyPlanChain()
    for weeks in (4, 12):
        request = build_request(weeks=weeks)
        response = chain._mock_response(request=request, request_id=f"plan-mock-{weeks}")  # noqa: SLF001
        assert_plan_quality(response, expected_weeks=weeks)


def test_partial_response_is_repaired() -> None:
    chain = WeeklyPlanChain()
    request = build_request(weeks=6)
    generated = GeneratedWeeklyPlan(
        overview="반드시 성공합니다.",
        weeklyPlan=[
            GeneratedWeeklyPlanItem(
                week=1,
                goal="무조건 완성",
                tasks=["핵심 개념 정리"],
                recommendedResources=[],
                checkpoint="진도 확인",
            )
        ],
        riskNotes=[],
    )

    response, fallback_reasons = chain._normalize_generated(  # noqa: SLF001
        request=request,
        generated=generated,
        request_id="plan-partial",
    )

    assert "missing_week_item" in fallback_reasons
    assert "insufficient_tasks" in fallback_reasons
    assert "insufficient_resources" in fallback_reasons
    assert_plan_quality(response, expected_weeks=6)


def test_internal_terms_are_removed_and_weekly_lists_are_limited() -> None:
    chain = WeeklyPlanChain()
    request = build_request(weeks=4)
    generated = GeneratedWeeklyPlan(
        overview="RAG 데이터와 snippet을 기준으로 계획합니다. 내부 데이터가 있습니다. 학습을 시작하세요.",
        weeklyPlan=[
            GeneratedWeeklyPlanItem(
                week=week,
                goal="majorContext 기준 목표",
                tasks=[
                    "ragSnippets 내용을 정리합니다.",
                    "RAG 근거로 문제를 풉니다.",
                    "snippet 기반 발표를 합니다.",
                    "스니펫 기준 피드백을 정리합니다.",
                    "내부 데이터 기준 추가 활동을 합니다.",
                ],
                recommendedResources=[
                    "rag 데이터 검색어",
                    "snippet 자료",
                    "스니펫 활동지",
                    "내부 데이터 목록",
                ],
                checkpoint="프롬프트 기준으로 완료 여부를 확인합니다.",
            )
            for week in range(1, 5)
        ],
        riskNotes=["RAG 오류", "snippet 과다", "스니펫 노출", "내부 데이터 노출"],
    )

    response, _ = chain._normalize_generated(  # noqa: SLF001
        request=request,
        generated=generated,
        request_id="plan-internal-term-test",
    )

    assert_plan_quality(response, expected_weeks=4)


def test_plan_prompt_contains_quality_rules() -> None:
    chain = WeeklyPlanChain()

    assert "내부 용어 노출 금지" in chain.prompt_text
    assert "RAG" in chain.prompt_text
    assert "snippet" in chain.prompt_text
    assert "tasks는 2~4개" in chain.prompt_text
    assert "recommendedResources는 1~3개" in chain.prompt_text
