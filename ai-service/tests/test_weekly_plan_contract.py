from __future__ import annotations

import re

from app.chain_plan import GeneratedWeeklyPlan, GeneratedWeeklyPlanItem, WeeklyPlanChain
from app.models import WeeklyPlanConstraints, WeeklyPlanRequest, WeeklyPlanTargetMajor
from app.models import Profile as PlanProfile

BANNED_WORDS = ("반드시", "무조건")


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


def sentence_count(text: str) -> int:
    return len([s for s in re.split(r"(?<=[.!?])\s+", text.strip()) if s.strip()])


def assert_plan_quality(response, expected_weeks: int) -> None:
    assert response.overview
    assert 3 <= sentence_count(response.overview) <= 5
    assert len(response.weeklyPlan) == expected_weeks

    for item in response.weeklyPlan:
        assert item.goal
        assert len(item.tasks) >= 2
        assert len(item.recommendedResources) >= 1
        assert item.checkpoint

        assert not any(b in item.goal for b in BANNED_WORDS)
        assert all(not any(b in task for b in BANNED_WORDS) for task in item.tasks)
        assert all(not any(b in res for b in BANNED_WORDS) for res in item.recommendedResources)


def test_weekly_plan_mock_generation_6_weeks() -> None:
    chain = WeeklyPlanChain()
    request = build_request(weeks=6)
    response = chain._mock_response(request=request, request_id="plan-mock-6")  # noqa: SLF001
    assert_plan_quality(response, expected_weeks=6)


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
