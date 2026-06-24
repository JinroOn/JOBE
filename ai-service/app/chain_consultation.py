from __future__ import annotations

import asyncio
import os
from dataclasses import dataclass

from langchain_core.messages import SystemMessage
from langchain_core.prompts import ChatPromptTemplate, HumanMessagePromptTemplate
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, ConfigDict, Field

from .errors import RequestTimeoutError, UpstreamUnavailableError
from .models import ConsultationChatRequest, ConsultationChatResponse

PROMPT_VERSION = os.getenv("CONSULTATION_PROMPT_VERSION", "consultation-chat-v1.0.0")

MISSING_CONTEXT_GUIDANCE = (
    "아직 진로온 전공 추천 결과가 없어 개인 맞춤형 답변을 바로 드리기는 어렵습니다. "
    "먼저 진로온의 전공 추천 진단을 진행하면, 추천 전공과 강점/보완점을 바탕으로 더 적합한 방향을 안내해드릴 수 있어요. "
    "진단 전이라면 관심 분야, 목표 직무, 현재 고민 중 하나를 알려주세요."
)


class GeneratedConsultationAnswer(BaseModel):
    model_config = ConfigDict(extra="forbid")

    content: str = Field(min_length=1, max_length=2000)


@dataclass
class ConsultationChainResult:
    response: ConsultationChatResponse
    fallback_reasons: list[str]
    prompt_version: str
    model: str


class ConsultationChain:
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
        self.prompt_version = PROMPT_VERSION

    async def generate_with_meta(
        self,
        request: ConsultationChatRequest,
        request_id: str,
    ) -> ConsultationChainResult:
        if self.use_mock:
            response = self._mock_response(request=request, request_id=request_id)
            return ConsultationChainResult(
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
            structured = llm.with_structured_output(GeneratedConsultationAnswer)
            prompt = self._build_prompt()
            chain = prompt | structured
            generated = await asyncio.wait_for(
                chain.ainvoke({"input_json": request.model_dump_json()}),
                timeout=self.timeout_seconds,
            )
            content = self._normalize_content(generated.content, request)
            return ConsultationChainResult(
                response=ConsultationChatResponse(
                    content=content,
                    version=self.prompt_version,
                    requestId=request_id,
                ),
                fallback_reasons=[],
                prompt_version=self.prompt_version,
                model=self.model,
            )
        except asyncio.TimeoutError as exc:
            raise RequestTimeoutError() from exc
        except UpstreamUnavailableError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise UpstreamUnavailableError("LLM provider request failed") from exc

    def _build_prompt(self) -> ChatPromptTemplate:
        return ChatPromptTemplate.from_messages(
            [
                SystemMessage(content=self._system_prompt()),
                HumanMessagePromptTemplate.from_template("Input JSON:\n{input_json}"),
            ]
        )

    def _mock_response(self, *, request: ConsultationChatRequest, request_id: str) -> ConsultationChatResponse:
        if self._needs_jinroon_diagnosis_guidance(request):
            content = MISSING_CONTEXT_GUIDANCE
        else:
            major_names = [
                item.majorName
                for item in (request.diagnosisContext.topMajors if request.diagnosisContext else [])
                if item.majorName
            ]
            weakness = request.diagnosisContext.weaknessFocus if request.diagnosisContext else []
            major_text = ", ".join(major_names[:3]) if major_names else "상담에서 언급한 관심 분야"
            weakness_text = ", ".join(weakness[:2]) if weakness else "현재 보완이 필요한 역량"
            dream_job = (
                request.diagnosisContext.profileContext.dreamJob
                if request.diagnosisContext
                and request.diagnosisContext.profileContext
                and request.diagnosisContext.profileContext.dreamJob
                else None
            )
            dream_job_text = f" 목표 직무인 {dream_job}도 함께 고려하면" if dream_job else ""
            content = (
                f"저장된 진단 결과와 상담 맥락을 보면 {major_text} 방향을 우선 기준으로 삼을 수 있습니다. "
                f"{dream_job_text} 구체적인 강의나 자격증 목록 데이터는 현재 제공된 컨텍스트에 없으므로, {weakness_text}을 보완하는 학습 주제와 실습 중심 리소스부터 추천하는 것이 안전합니다. "
                "목표 직무나 관심 전공을 조금 더 구체적으로 알려주면 우선순위와 준비 순서를 더 좁혀드릴 수 있습니다."
            )
        return ConsultationChatResponse(
            content=content[:2000],
            version=self.prompt_version,
            requestId=request_id,
        )

    def _normalize_content(self, content: str, request: ConsultationChatRequest) -> str:
        text = " ".join((content or "").split())
        if not text:
            return self._mock_response(request=request, request_id="fallback").content
        if self._needs_jinroon_diagnosis_guidance(request):
            risky_markers = ("추천합니다", "취득하세요", "강의 리스트")
            required_markers = ("진로온", "전공 추천", "진단")
            if any(marker in text for marker in risky_markers) or not all(marker in text for marker in required_markers):
                return self._mock_response(request=request, request_id="fallback").content
        return text[:2000]

    def _needs_jinroon_diagnosis_guidance(self, request: ConsultationChatRequest) -> bool:
        has_diagnosis_context = request.hasDiagnosisContext or request.diagnosisContext is not None
        return not has_diagnosis_context and len(request.history) <= 1

    def _system_prompt(self) -> str:
        return (
            "You are Jinroon's AI career and major consultation assistant, not a generic chatbot. "
            "Answer in Korean and keep the response aware of Jinroon's service flow. "
            "Use only the provided consultation history and diagnosis context as evidence. "
            "If diagnosis context and meaningful history are missing, first explain that personalized guidance needs Jinroon's major recommendation diagnosis result. "
            "Then recommend using Jinroon's major recommendation/diagnosis feature before asking at most 2 or 3 focused follow-up questions. "
            "If no diagnosis result exists but meaningful consultation history exists, say the answer is based on the conversation so far and still recommend Jinroon diagnosis for stronger personalization. "
            "Do not invent exact in-app course lists, official certificate lists, external links, or database-backed resources. "
            "For course or certificate questions, recommend candidate directions, learning resource types, and preparation priorities only when the provided context supports them. "
            "Avoid guaranteeing admission, employment, certification, or other high-stakes outcomes. "
            "Return concise, practical guidance in 2 to 5 sentences."
        )
