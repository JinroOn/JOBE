from __future__ import annotations

import asyncio
import os
import re
import uuid
from dataclasses import dataclass
from pathlib import Path

from langchain_core.prompts import ChatPromptTemplate
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, ConfigDict, Field

from .errors import RequestTimeoutError, UpstreamUnavailableError
from .models import WeeklyPlanItem, WeeklyPlanRequest, WeeklyPlanResponse
from .rag.context import enrich_weekly_plan_request_with_rag

PLAN_PROMPT_VERSION = os.getenv("PLAN_PROMPT_VERSION", "plan-v1.0.0")
PLAN_PROMPT_FILE = os.getenv("PLAN_PROMPT_FILE", "plan_v1_0.txt")
BANNED_ASSERTIVE_WORDS = ("반드시", "무조건")
INTERNAL_TERM_REPLACEMENTS = (
    (re.compile(r"ragSnippets", re.IGNORECASE), "전공 근거"),
    (re.compile(r"majorContext", re.IGNORECASE), "전공 정보"),
    (re.compile(r"rag\s*데이터", re.IGNORECASE), "전공 근거"),
    (re.compile(r"snippet", re.IGNORECASE), "전공 근거"),
    (re.compile(r"rag", re.IGNORECASE), "전공 근거"),
    (re.compile(r"스니펫"), "전공 근거"),
    (re.compile(r"검색\s*조각"), "전공 근거"),
    (re.compile(r"내부\s*데이터"), "전공 근거"),
    (re.compile(r"프롬프트"), "응답 기준"),
)


class GeneratedWeeklyPlanItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    week: int = Field(ge=1, le=12)
    goal: str = Field(min_length=1, max_length=300)
    tasks: list[str] = Field(min_length=1, max_length=8)
    recommendedResources: list[str] = Field(min_length=0, max_length=8)
    checkpoint: str = Field(min_length=1, max_length=300)


class GeneratedWeeklyPlan(BaseModel):
    model_config = ConfigDict(extra="forbid")

    overview: str = Field(min_length=1, max_length=1200)
    weeklyPlan: list[GeneratedWeeklyPlanItem] = Field(min_length=1, max_length=12)
    riskNotes: list[str] = Field(default_factory=list, max_length=10)


@dataclass
class PlanChainResult:
    response: WeeklyPlanResponse
    fallback_reasons: list[str]
    prompt_version: str
    model: str


class WeeklyPlanChain:
    def __init__(self) -> None:
        self.provider = os.getenv("LLM_PROVIDER", "openai").lower()
        self.model = os.getenv("LLM_MODEL", "gpt-4o-mini")
        self.timeout_seconds = float(os.getenv("LLM_TIMEOUT_SECONDS", "10"))
        self.api_key = (
            os.getenv("LLM_API_KEY")
            or os.getenv("OPENAI_API_KEY")
            or os.getenv("FACTCHAT_API_KEY")
            or ""
        )
        self.base_url = os.getenv("LLM_BASE_URL") or os.getenv("OPENAI_BASE_URL") or ""
        self.use_mock = os.getenv("MOCK_MODE", "").lower() == "true" or not self.api_key
        self.prompt_version = PLAN_PROMPT_VERSION
        self.prompt_text = self._load_prompt_text()

    async def generate(self, request: WeeklyPlanRequest, request_id: str) -> WeeklyPlanResponse:
        result = await self.generate_with_meta(request=request, request_id=request_id)
        return result.response

    async def generate_with_meta(self, request: WeeklyPlanRequest, request_id: str) -> PlanChainResult:
        if self.use_mock:
            response = self._mock_response(request=request, request_id=request_id)
            return PlanChainResult(
                response=response,
                fallback_reasons=["mock_mode_enabled"],
                prompt_version=self.prompt_version,
                model=self.model,
            )

        if self.provider not in {"openai", "openai_compatible", "factchat"}:
            raise UpstreamUnavailableError(f"Unsupported provider: {self.provider}")

        try:
            llm = ChatOpenAI(
                model=self.model,
                api_key=self.api_key,
                timeout=self.timeout_seconds,
                base_url=self.base_url or None,
            )
            structured = llm.with_structured_output(GeneratedWeeklyPlan)
            prompt = ChatPromptTemplate.from_messages(
                [
                    ("system", self.prompt_text),
                    ("human", "Input JSON:\n{input_json}"),
                ]
            )
            chain = prompt | structured
            enriched_request, rag_fallback_reasons = enrich_weekly_plan_request_with_rag(
                request,
                request_id=request_id,
            )
            payload_json = enriched_request.model_dump_json()
            generated = await asyncio.wait_for(
                chain.ainvoke({"input_json": payload_json}),
                timeout=self.timeout_seconds,
            )
            response, fallback_reasons = self._normalize_generated(
                request=enriched_request,
                generated=generated,
                request_id=request_id,
            )
            fallback_reasons.extend(rag_fallback_reasons)
            return PlanChainResult(
                response=response,
                fallback_reasons=sorted(set(fallback_reasons)),
                prompt_version=self.prompt_version,
                model=self.model,
            )
        except asyncio.TimeoutError as exc:
            raise RequestTimeoutError() from exc
        except UpstreamUnavailableError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise UpstreamUnavailableError("LLM provider request failed") from exc

    def _normalize_generated(
        self,
        *,
        request: WeeklyPlanRequest,
        generated: GeneratedWeeklyPlan,
        request_id: str,
    ) -> tuple[WeeklyPlanResponse, list[str]]:
        fallback_reasons: list[str] = []
        weeks = request.constraints.weeks
        by_week = {item.week: item for item in generated.weeklyPlan}
        weekly_plan: list[WeeklyPlanItem] = []

        for week_num in range(1, weeks + 1):
            item = by_week.get(week_num)
            if item is None:
                fallback_reasons.append("missing_week_item")

            goal = self._sanitize_text(item.goal if item else "")
            if not goal:
                fallback_reasons.append("missing_goal")
                goal = f"{week_num}주차 목표는 {request.targetMajor.majorName} 관련 기초 개념을 보완하는 것입니다."

            tasks = [self._sanitize_text(task) for task in (item.tasks if item else [])]
            tasks = [task for task in tasks if task]
            if len(tasks) < 2:
                fallback_reasons.append("insufficient_tasks")
                tasks.extend(self._fallback_tasks(request=request, week_num=week_num))
            tasks = [self._trim(self._replace_banned_words(task), 140) for task in tasks[:4]]

            resources = [self._sanitize_text(res) for res in (item.recommendedResources if item else [])]
            resources = [res for res in resources if res]
            if len(resources) < 1:
                fallback_reasons.append("insufficient_resources")
                resources.extend(self._fallback_resources(request=request, week_num=week_num))
            resources = [self._trim(self._replace_banned_words(res), 120) for res in resources[:3]]

            checkpoint = self._sanitize_text(item.checkpoint if item else "")
            if not checkpoint:
                fallback_reasons.append("missing_checkpoint")
                checkpoint = f"{week_num}주차 말에 학습 개념 요약과 문제 풀이 결과를 점검합니다."

            weekly_plan.append(
                WeeklyPlanItem(
                    week=week_num,
                    goal=self._trim(self._replace_banned_words(goal), 300),
                    tasks=tasks,
                    recommendedResources=resources,
                    checkpoint=self._trim(self._replace_banned_words(checkpoint), 300),
                )
            )

        overview = self._normalize_overview(generated.overview, request=request)
        risk_notes = [self._sanitize_text(note) for note in generated.riskNotes]
        risk_notes = [self._trim(self._replace_banned_words(note), 200) for note in risk_notes if note][:3]

        response = WeeklyPlanResponse(
            planId=f"plan-{request.sessionId}-{uuid.uuid4().hex[:8]}",
            version=self.prompt_version,
            overview=overview,
            weeklyPlan=weekly_plan,
            riskNotes=risk_notes,
            requestId=request_id,
        )
        return response, sorted(set(fallback_reasons))

    def _normalize_overview(self, overview: str | None, *, request: WeeklyPlanRequest) -> str:
        text = self._sanitize_text(overview or "")
        if not text:
            text = (
                f"{request.targetMajor.majorName} 진입을 위한 {request.constraints.weeks}주 학습 계획입니다. "
                "주차별 목표를 작게 나누어 실행하고, 주말 체크포인트로 학습 진척을 확인하세요. "
                "취약 역량은 반복 연습과 피드백으로 단계적으로 개선하는 방식을 권장합니다."
            )
        text = self._replace_banned_words(text)
        sentences = self._split_sentences(text)
        while len(sentences) < 3:
            sentences.append("학습 기록표를 만들어 주간 목표 달성 여부를 확인하면 실행률이 높아집니다.")
        if len(sentences) > 5:
            sentences = sentences[:5]
        return self._trim(" ".join(self._ensure_period(s) for s in sentences), 1200)

    def _fallback_tasks(self, *, request: WeeklyPlanRequest, week_num: int) -> list[str]:
        weakness = request.weaknessFocus[0]
        return [
            f"{week_num}주차에는 {weakness} 관련 핵심 개념을 2회 복습합니다.",
            f"주 {request.constraints.studyHoursPerWeek}시간 범위에서 문제 풀이 학습을 2회 진행합니다.",
        ]

    def _fallback_resources(self, *, request: WeeklyPlanRequest, week_num: int) -> list[str]:
        return [
            f"{request.targetMajor.majorName} 기초 강의 또는 교재 1종",
            f"{week_num}주차 연습 문제 세트",
        ]

    def _sanitize_text(self, text: str) -> str:
        cleaned = text.replace("\n", " ").replace("\r", " ").strip()
        cleaned = re.sub(r"[#*_`>{}\[\]|~]", " ", cleaned)
        cleaned = re.sub(r"[!@\$%\^&\+=]{2,}", " ", cleaned)
        cleaned = self._replace_internal_terms(cleaned)
        cleaned = re.sub(r"\s+", " ", cleaned).strip()
        return cleaned

    def _split_sentences(self, text: str) -> list[str]:
        chunks = re.split(r"(?<=[.!?])\s+", text.strip())
        return [chunk.strip() for chunk in chunks if chunk.strip()]

    def _ensure_period(self, sentence: str) -> str:
        s = sentence.strip()
        if not s:
            return s
        if s[-1] not in ".!?":
            return s + "."
        return s

    def _replace_banned_words(self, text: str) -> str:
        out = text
        for banned in BANNED_ASSERTIVE_WORDS:
            out = out.replace(banned, "권장")
        return out

    def _replace_internal_terms(self, text: str) -> str:
        out = text
        for pattern, replacement in INTERNAL_TERM_REPLACEMENTS:
            out = pattern.sub(replacement, out)
        return out

    def _trim(self, text: str, max_len: int) -> str:
        return text if len(text) <= max_len else text[:max_len]

    def _mock_response(self, *, request: WeeklyPlanRequest, request_id: str) -> WeeklyPlanResponse:
        generated = GeneratedWeeklyPlan(
            overview=(
                f"{request.targetMajor.majorName} 준비를 위한 {request.constraints.weeks}주 계획입니다. "
                "주차별 목표를 분할해 학습하고, 주말 체크포인트로 진행률을 점검하세요. "
                "취약 역량은 반복 훈련으로 개선하는 방식을 권장합니다."
            ),
            weeklyPlan=[
                GeneratedWeeklyPlanItem(
                    week=week_num,
                    goal=f"{week_num}주차에는 취약 역량 기초를 학습합니다.",
                    tasks=[
                        "핵심 개념 정리 노트를 작성합니다.",
                        "연습 문제 풀이를 2회 진행합니다.",
                    ],
                    recommendedResources=["기초 강의 1개", "주차별 문제 세트"],
                    checkpoint="학습 내용 요약과 정답률을 확인합니다.",
                )
                for week_num in range(1, request.constraints.weeks + 1)
            ],
            riskNotes=["학습 시간이 부족하면 주차별 과제를 축소하고 복습 과제를 우선 수행하세요."],
        )
        response, _ = self._normalize_generated(request=request, generated=generated, request_id=request_id)
        return response

    def _load_prompt_text(self) -> str:
        prompt_path = Path(__file__).resolve().parent / "prompts" / PLAN_PROMPT_FILE
        if prompt_path.exists():
            return prompt_path.read_text(encoding="utf-8")
        return (
            "당신은 학습 코치 시스템입니다. 입력 JSON을 기반으로 4~12주 학습 플랜을 JSON 형식으로 작성하세요. "
            "RAG, snippet, 내부 데이터 같은 내부 용어는 최종 응답에 쓰지 마세요."
        )
