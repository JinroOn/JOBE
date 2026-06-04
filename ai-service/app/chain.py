from __future__ import annotations

import asyncio
import os
import re
from dataclasses import dataclass
from pathlib import Path

from langchain_core.prompts import ChatPromptTemplate
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, ConfigDict, Field

from .errors import RequestTimeoutError, UpstreamUnavailableError
from .models import MajorComment, RecommendationCommentRequest, RecommendationCommentResponse
from .rag.context import enrich_recommendation_request_with_rag

PROMPT_VERSION = os.getenv("PROMPT_VERSION", "rec-comment-v1.2.0")
PROMPT_FILE = os.getenv("PROMPT_FILE", "recommendation_v1_1.txt")
WEAKNESS_KEYS = {
    "mathLogicalScore",
    "problemSolvingScore",
    "infoTechUtilizationScore",
    "softwareImplementationScore",
    "systemUnderstandingScore",
    "dataAnalysisScore",
    "communicationScore",
    "collaborationScore",
    "selfManagementScore",
}
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
ACTION_KEYWORDS = ("추천", "권장", "연습", "보완", "개선", "훈련", "시도")
DIFFERENTIATION_MARKERS = (
    "반면",
    "다른 추천 전공과 비교하면",
    "이 전공은 특히",
    "선택 기준은",
    "보다",
    "중심",
)


class GeneratedMajorComment(BaseModel):
    model_config = ConfigDict(extra="forbid")

    majorName: str = Field(min_length=1, max_length=100)
    rankingOrder: int = Field(ge=1, le=5)
    fitScore: float = Field(ge=0, le=100)
    strengths: str | None = Field(default=None, max_length=500)
    weaknesses: str | None = Field(default=None, max_length=500)
    recommendationReason: str = Field(min_length=1, max_length=800)


class GeneratedRecommendation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    summaryComment: str = Field(min_length=1, max_length=1200)
    majorComments: list[GeneratedMajorComment] = Field(min_length=1, max_length=5)
    weaknessFocus: list[str] = Field(default_factory=list, max_length=2)


@dataclass
class ChainResult:
    response: RecommendationCommentResponse
    fallback_reasons: list[str]
    prompt_version: str
    model: str


class RecommendationChain:
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
        self.prompt_text = self._load_prompt_text()

    async def generate(self, request: RecommendationCommentRequest, request_id: str) -> RecommendationCommentResponse:
        result = await self.generate_with_meta(request=request, request_id=request_id)
        return result.response

    async def generate_with_meta(self, request: RecommendationCommentRequest, request_id: str) -> ChainResult:
        if self.use_mock:
            response = self._mock_response(request=request, request_id=request_id)
            return ChainResult(
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
            structured = llm.with_structured_output(GeneratedRecommendation)
            prompt = ChatPromptTemplate.from_messages(
                [
                    ("system", self.prompt_text),
                    ("human", "Input JSON:\n{input_json}"),
                ]
            )
            chain = prompt | structured
            enriched_request, rag_fallback_reasons = enrich_recommendation_request_with_rag(
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
            return ChainResult(
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
        request: RecommendationCommentRequest,
        generated: GeneratedRecommendation,
        request_id: str,
    ) -> tuple[RecommendationCommentResponse, list[str]]:
        fallback_reasons: list[str] = []
        comments: list[MajorComment] = []
        by_rank: dict[int, GeneratedMajorComment] = {item.rankingOrder: item for item in generated.majorComments}
        input_major_names = [item.majorName for item in request.topMajors]
        group_context = self._group_context_by_major(request)

        for top_major in sorted(request.topMajors, key=lambda m: m.rankingOrder):
            chosen = by_rank.get(top_major.rankingOrder)
            if not chosen:
                fallback_reasons.append("missing_major_comment")

            major_name = top_major.majorName
            fit_score = float(top_major.fitScore)
            raw_reason = (
                chosen.recommendationReason.strip()
                if chosen and chosen.recommendationReason and chosen.recommendationReason.strip()
                else f"{major_name} 전공은 현재 역량과 전공 요구 역량의 적합도가 높아 추천됩니다."
            )
            if not chosen or not chosen.recommendationReason or not chosen.recommendationReason.strip():
                fallback_reasons.append("missing_recommendation_reason")

            strengths_text = (
                chosen.strengths.strip()
                if chosen and chosen.strengths and chosen.strengths.strip()
                else (top_major.strengths or "")
            )
            if not strengths_text:
                fallback_reasons.append("missing_strengths")

            weaknesses_text = (
                chosen.weaknesses.strip()
                if chosen and chosen.weaknesses and chosen.weaknesses.strip()
                else (top_major.weaknesses or "")
            )
            if not weaknesses_text:
                fallback_reasons.append("missing_weaknesses")

            strengths_sentence = self._normalize_attribute_sentence(
                strengths_text,
                fallback="강점은 문제 해결력과 학습 지속성에서 확인됩니다.",
                prefix="강점은",
            )
            weaknesses_sentence = self._normalize_attribute_sentence(
                weaknesses_text,
                fallback="보완점은 의사소통과 시스템 이해 역량입니다.",
                prefix="보완점은",
            )
            group_item_context = group_context.get(major_name, {})
            reason_sentence = self._normalize_reason_text(
                raw_reason,
                major_name=major_name,
                weakness_hint=weaknesses_sentence,
                comparison_majors=group_item_context.get(
                    "comparison_majors",
                    [name for name in input_major_names if name != major_name],
                ),
                differentiation_hint=group_item_context.get("differentiation_hint"),
            )

            comments.append(
                MajorComment(
                    majorName=major_name,
                    rankingOrder=top_major.rankingOrder,
                    fitScore=fit_score,
                    strengths=self._trim(strengths_sentence, 500),
                    weaknesses=self._trim(weaknesses_sentence, 500),
                    recommendationReason=self._trim(reason_sentence, 800),
                )
            )

        weakness_focus = [key for key in generated.weaknessFocus if key in WEAKNESS_KEYS][:2]
        if len(generated.weaknessFocus) != len(weakness_focus):
            fallback_reasons.append("invalid_weakness_focus_removed")

        summary = self._normalize_summary_text(
            summary=generated.summaryComment,
            weakness_focus=weakness_focus,
            recommendation_groups=request.recommendationGroups,
        )

        unique_fallbacks = sorted(set(fallback_reasons))
        return RecommendationCommentResponse(
            summaryComment=self._trim(summary, 1200),
            majorComments=comments,
            weaknessFocus=weakness_focus,
            version=self.prompt_version,
            requestId=request_id,
        ), unique_fallbacks

    def _normalize_summary_text(
        self,
        summary: str | None,
        *,
        weakness_focus: list[str],
        recommendation_groups: list | None = None,
    ) -> str:
        text = self._sanitize_text(summary or "")
        if not text:
            text = (
                "전반적으로 전공 적합도는 양호한 편입니다. "
                "강점 역량은 유지하고 부족한 역량은 계획적으로 보완하면 진학 후 적응력이 높아집니다. "
                "주 2회 이상 약점 영역을 집중 학습하는 루틴을 권장합니다."
            )
        sentences = self._split_sentences(text)
        if len(sentences) < 3:
            sentences.append("강점은 유지하고 약점은 작은 단위 목표로 나눠 보완하는 접근이 효과적입니다.")
        if len(sentences) < 4:
            hint = ", ".join(weakness_focus) if weakness_focus else "의사소통과 시스템 이해"
            sentences.append(f"우선 보완 영역은 {hint}이며, 주간 학습 루틴으로 반복 훈련을 권장합니다.")
        group_hint = self._summary_group_hint(recommendation_groups or [])
        if group_hint and len(sentences) < 5 and not any(marker in text for marker in ("공통", "선택", "비교", "기준")):
            sentences.append(group_hint)
        if not any(marker in " ".join(sentences) for marker in ("공통", "선택", "비교", "기준")):
            marker_sentence = "추천 선택 기준은 강점 역량과 보완 가능성을 함께 비교해 정하면 좋습니다."
            if len(sentences) >= 5:
                sentences[-1] = marker_sentence
            else:
                sentences.append(marker_sentence)
        if len(sentences) > 5:
            sentences = sentences[:5]
        sentences = [self._ensure_period(s) for s in sentences]
        merged = self._replace_banned_words(" ".join(sentences))
        if not self._is_mostly_korean(merged):
            merged = (
                "현재 역량 데이터를 보면 전공 적합도는 비교적 안정적입니다. "
                "강점 역량은 유지하고 부족한 역량은 주간 계획으로 보완하는 것이 좋습니다. "
                "실습 프로젝트 학습과 발표 연습을 병행하면 실전 적응력이 향상됩니다."
            )
        return merged

    def _group_context_by_major(self, request: RecommendationCommentRequest) -> dict[str, dict[str, object]]:
        context: dict[str, dict[str, object]] = {}
        for group in request.recommendationGroups:
            group_names = [group.representativeMajorName, *group.similarMajorNames]
            descriptions = {point.majorName: point.description for point in group.differencePoints}
            for point in group.differencePoints:
                if point.majorName not in group_names:
                    group_names.append(point.majorName)
            for major_name in group_names:
                context[major_name] = {
                    "comparison_majors": [name for name in group_names if name != major_name],
                    "differentiation_hint": descriptions.get(major_name),
                }
        return context

    def _summary_group_hint(self, recommendation_groups: list) -> str | None:
        if not recommendation_groups:
            return None
        first = recommendation_groups[0]
        axes = ", ".join(first.commonFitAxes[:3])
        if not axes:
            return None
        majors = [first.representativeMajorName, *first.similarMajorNames]
        major_text = ", ".join(majors[:3])
        return f"공통 적합 근거는 {axes}이며, 선택 기준은 {major_text}의 차이를 비교해서 정하면 좋습니다."

    def _normalize_attribute_sentence(self, text: str, *, fallback: str, prefix: str) -> str:
        cleaned = self._sanitize_text(text)
        if not cleaned:
            return fallback

        if "," in cleaned and "." not in cleaned:
            keywords = [token.strip() for token in cleaned.split(",") if token.strip()]
            if keywords:
                cleaned = f"{prefix} {', '.join(keywords)} 역량에서 확인됩니다."

        sentences = self._split_sentences(cleaned)
        if not sentences:
            return fallback
        sentence = self._replace_banned_words(sentences[0])
        if sentence[-1] not in ".!?":
            sentence += "."
        if not self._is_mostly_korean(sentence):
            return fallback
        return sentence

    def _normalize_reason_text(
        self,
        text: str,
        *,
        major_name: str,
        weakness_hint: str,
        comparison_majors: list[str] | None = None,
        differentiation_hint: str | None = None,
    ) -> str:
        cleaned = self._sanitize_text(text)
        if not cleaned:
            cleaned = f"{major_name} 전공은 현재 역량과 전공 요구 역량의 비교 결과가 양호합니다."

        sentences = self._split_sentences(cleaned)
        focus = self._fallback_focus(major_name)
        action = self._fallback_action(major_name)
        if len(sentences) < 2:
            sentences.append(f"{focus} 관련 기초 활동을 먼저 정리하면 전공 적응에 도움이 됩니다.")
        if len(sentences) < 3:
            weak = (
                weakness_hint.replace("보완점은 ", "")
                .replace(" 역량에서 확인됩니다.", "")
                .replace("에서 확인됩니다.", "")
                .replace(".", "")
            )
            sentences.append(f"{weak}을 보완하기 위해 {action}을 권장합니다.")
        if len(sentences) > 3:
            sentences = sentences[:3]

        sentences = [self._ensure_period(s) for s in sentences]
        merged = self._replace_banned_words(" ".join(sentences))
        if not any(keyword in merged for keyword in ACTION_KEYWORDS):
            merged += " 부족한 역량은 주간 학습 계획으로 보완하는 것을 권장합니다."

        if not any(marker in merged for marker in DIFFERENTIATION_MARKERS):
            comparison_names = [name for name in comparison_majors or [] if name and name != major_name]
            if differentiation_hint:
                hint = self._sanitize_text(str(differentiation_hint))
                merged += f" 다른 추천 전공과 비교하면, {major_name}은 {hint}"
                if merged[-1] not in ".!?":
                    merged += "."
            elif comparison_names:
                merged += (
                    f" 다른 추천 전공과 비교하면, {major_name}은 "
                    f"{', '.join(comparison_names[:2])}와의 공통 역량을 바탕으로 하되 "
                    f"{focus}에 더 중심을 두는 선택 기준으로 삼기 좋습니다."
                )
            else:
                merged += f" 이 전공은 특히 {focus}에 중심을 두고 적합도를 확인하면 좋습니다."

        sentences = self._split_sentences(merged)
        if len(sentences) > 3:
            last_sentence = sentences[-1]
            if any(marker in last_sentence for marker in DIFFERENTIATION_MARKERS):
                action_sentence = next(
                    (
                        sentence
                        for sentence in sentences[1:-1]
                        if any(keyword in sentence for keyword in ACTION_KEYWORDS)
                    ),
                    sentences[1],
                )
                sentences = [sentences[0], action_sentence, last_sentence]
            else:
                sentences = sentences[:3]
            merged = " ".join(self._ensure_period(sentence) for sentence in sentences)

        if not self._is_mostly_korean(merged):
            merged = (
                f"{major_name} 전공은 현재 역량과 전공 요구 역량의 비교 결과가 양호합니다. "
                "강점 과목은 유지하고 부족한 역량은 작은 학습 목표로 보완해 보세요. "
                "실습 과제와 발표 연습을 병행하면 전공 적응력이 향상됩니다."
            )
        return merged

    def _fallback_focus(self, major_name: str) -> str:
        if "컴퓨터" in major_name:
            return "소프트웨어 구현과 컴퓨팅 시스템"
        if "데이터" in major_name or "통계" in major_name:
            return "데이터 모델링과 분석"
        if "로봇" in major_name:
            return "센서와 제어 시스템"
        if "정보통신" in major_name:
            return "네트워크와 통신 인프라"
        if "전자" in major_name or "반도체" in major_name:
            return "회로와 전자 장치"
        if "경영" in major_name:
            return "조직 운영과 시장 판단"
        if "미디어" in major_name:
            return "콘텐츠 기획과 디지털 전달"
        if "간호" in major_name:
            return "환자 이해와 임상 판단"
        if "환경" in major_name:
            return "환경 시스템과 현장 데이터"
        if "식품" in major_name:
            return "식품 품질과 안전성"
        if "생명" in major_name:
            return "생명 현상과 실험 탐구"
        return "전공 고유의 학습 주제"

    def _fallback_action(self, major_name: str) -> str:
        if "컴퓨터" in major_name:
            return "작은 프로그램 구현 연습"
        if "데이터" in major_name or "통계" in major_name:
            return "자료 해석과 시각화 훈련"
        if "로봇" in major_name:
            return "센서 제어 실습"
        if "정보통신" in major_name:
            return "네트워크 구조 정리"
        if "전자" in major_name or "반도체" in major_name:
            return "회로 개념 문제 풀이"
        if "경영" in major_name:
            return "사례 분석과 발표 연습"
        if "미디어" in major_name:
            return "콘텐츠 기획안 작성"
        if "간호" in major_name:
            return "상황 판단 기록 훈련"
        if "환경" in major_name:
            return "환경 이슈 데이터 정리"
        if "식품" in major_name:
            return "식품 성분 비교 활동"
        if "생명" in major_name:
            return "실험 과정 요약 연습"
        return "기초 개념 정리와 짧은 실습"

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

    def _is_mostly_korean(self, text: str) -> bool:
        if not text:
            return False
        hangul = len(re.findall(r"[가-힣]", text))
        alpha_num = len(re.findall(r"[A-Za-z0-9가-힣]", text))
        if alpha_num == 0:
            return False
        return (hangul / alpha_num) >= 0.30

    def _trim(self, text: str, max_len: int) -> str:
        return text if len(text) <= max_len else text[:max_len]

    def _mock_response(self, *, request: RecommendationCommentRequest, request_id: str) -> RecommendationCommentResponse:
        profile_dict: dict[str, int] = request.profile.model_dump()
        sorted_weak = sorted(profile_dict.items(), key=lambda item: item[1])[:2]
        weakness_focus = [k for k, _ in sorted_weak]
        group_context = self._group_context_by_major(request)

        major_comments: list[MajorComment] = []
        for item in sorted(request.topMajors, key=lambda x: x.rankingOrder):
            strengths = self._normalize_attribute_sentence(
                item.strengths or "",
                fallback="강점은 문제 해결력과 학습 지속성에서 확인됩니다.",
                prefix="강점은",
            )
            weaknesses = self._normalize_attribute_sentence(
                item.weaknesses or "",
                fallback="보완점은 의사소통과 시스템 이해 역량입니다.",
                prefix="보완점은",
            )
            group_item_context = group_context.get(item.majorName, {})
            reason = self._normalize_reason_text(
                f"{item.majorName} 전공의 적합도는 {item.fitScore:.1f}점입니다.",
                major_name=item.majorName,
                weakness_hint=weaknesses,
                comparison_majors=group_item_context.get(
                    "comparison_majors",
                    [major.majorName for major in request.topMajors if major.majorName != item.majorName],
                ),
                differentiation_hint=group_item_context.get("differentiation_hint"),
            )
            major_comments.append(
                MajorComment(
                    majorName=item.majorName,
                    rankingOrder=item.rankingOrder,
                    fitScore=float(item.fitScore),
                    strengths=self._trim(strengths, 500),
                    weaknesses=self._trim(weaknesses, 500),
                    recommendationReason=self._trim(reason, 800),
                )
            )

        summary = self._normalize_summary_text(
            "전체적으로 학습 잠재력이 높습니다. 강점은 유지하고 보완점은 단계적으로 개선하세요.",
            weakness_focus=weakness_focus[:2],
            recommendation_groups=request.recommendationGroups,
        )

        return RecommendationCommentResponse(
            summaryComment=self._trim(summary, 1200),
            majorComments=major_comments,
            weaknessFocus=weakness_focus[:2],
            version=self.prompt_version,
            requestId=request_id,
        )

    def _load_prompt_text(self) -> str:
        prompt_path = Path(__file__).resolve().parent / "prompts" / PROMPT_FILE
        if prompt_path.exists():
            return prompt_path.read_text(encoding="utf-8")
        return (
            "당신은 진로 코칭 시스템입니다. "
            "출력은 JSON 구조만 따르고 markdown은 사용하지 마세요. "
            "recommendationReason은 2~3문장, summaryComment는 3~5문장으로 작성하세요. "
            "RAG, snippet, 내부 데이터 같은 내부 용어는 최종 응답에 쓰지 마세요."
        )
