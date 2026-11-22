from __future__ import annotations

import asyncio
import os
import re
from dataclasses import dataclass
from pathlib import Path

from langchain_core.messages import SystemMessage
from langchain_core.prompts import ChatPromptTemplate, HumanMessagePromptTemplate
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, ConfigDict, Field

from .errors import UpstreamUnavailableError
from .llm_options import DEFAULT_LLM_MODEL, structured_chat_options
from .models import MajorComment, Profile, RecommendationCommentRequest, RecommendationCommentResponse, WeaknessField
from .rag.context import enrich_recommendation_request_with_rag

PROMPT_VERSION = os.getenv("PROMPT_VERSION", "rec-comment-v1.3.0")
PROMPT_FILE = os.getenv("PROMPT_FILE", "recommendation_v1_1.txt")
AXIS_DISPLAY_NAMES = {
    "mathLogicalScore": "수리·논리",
    "problemSolvingScore": "문제 해결",
    "infoTechUtilizationScore": "정보기술 활용",
    "softwareImplementationScore": "구현력",
    "systemUnderstandingScore": "시스템 이해",
    "dataAnalysisScore": "자료 분석",
    "communicationScore": "의사소통",
    "collaborationScore": "협업",
    "selfManagementScore": "자기관리",
}
# Keep ties in the shared competency display order.
def competency_feedback(profile: Profile) -> tuple[list[WeaknessField], list[WeaknessField]]:
    scores = profile.model_dump()
    lowest, highest = min(scores.values()), max(scores.values())
    weaknesses = sorted((key for key in AXIS_DISPLAY_NAMES if scores[key] < highest), key=scores.__getitem__)[:2]
    strengths = sorted((key for key in AXIS_DISPLAY_NAMES if scores[key] > lowest and key not in weaknesses),
                       key=lambda key: -scores[key])[:2]
    return strengths, weaknesses


def competency_sentence(axes: list[WeaknessField], *, strength: bool) -> str:
    if not axes:
        return ("응답에서 상대적으로 높은 역량이 구분되지 않습니다." if strength
                else "응답에서 우선 보완할 역량이 구분되지 않습니다.")
    labels = ", ".join(AXIS_DISPLAY_NAMES[key] for key in axes)
    if strength:
        return f"현재 응답에서 상대적으로 높은 역량은 {labels}입니다."
    return f"현재 응답에서 우선 살펴볼 보완 영역은 {labels}입니다."


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
        self.model = os.getenv("LLM_MODEL") or DEFAULT_LLM_MODEL
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
            return self._fallback_chain_result(
                request=request,
                request_id=request_id,
                reason="unsupported_provider_fallback",
            )

        try:
            llm = ChatOpenAI(
                model=self.model,
                api_key=self.api_key,
                timeout=self.timeout_seconds,
                base_url=self.base_url or None,
                **structured_chat_options(self.model),
            )
            structured = llm.with_structured_output(GeneratedRecommendation)
            prompt = self._build_prompt()
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
        except asyncio.TimeoutError:
            return self._fallback_chain_result(
                request=request,
                request_id=request_id,
                reason="llm_timeout_fallback",
            )
        except UpstreamUnavailableError:
            return self._fallback_chain_result(
                request=request,
                request_id=request_id,
                reason="llm_unavailable_fallback",
            )
        except Exception:  # noqa: BLE001
            return self._fallback_chain_result(
                request=request,
                request_id=request_id,
                reason="llm_provider_fallback",
            )

    def _build_prompt(self) -> ChatPromptTemplate:
        return ChatPromptTemplate.from_messages(
            [
                SystemMessage(content=self.prompt_text),
                HumanMessagePromptTemplate.from_template("Input JSON:\n{input_json}"),
            ]
        )

    def _fallback_chain_result(
        self,
        *,
        request: RecommendationCommentRequest,
        request_id: str,
        reason: str,
    ) -> ChainResult:
        response = self._mock_response(request=request, request_id=request_id)
        return ChainResult(
            response=response,
            fallback_reasons=[reason],
            prompt_version=self.prompt_version,
            model=self.model,
        )

    def _normalize_generated(
        self,
        *,
        request: RecommendationCommentRequest,
        generated: GeneratedRecommendation,
        request_id: str,
    ) -> tuple[RecommendationCommentResponse, list[str]]:
        fallback_reasons: list[str] = []
        strengths, weakness_focus = competency_feedback(request.profile)
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

            group_item_context = group_context.get(major_name, {})
            reason_sentence = self._normalize_reason_text(
                self._without_competency_assessment(raw_reason),
                major_name=major_name,
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
                    strengths=competency_sentence(strengths, strength=True),
                    weaknesses=competency_sentence(weakness_focus, strength=False),
                    recommendationReason=self._trim(reason_sentence, 800),
                )
            )

        if generated.weaknessFocus and generated.weaknessFocus != weakness_focus:
            fallback_reasons.append("model_weakness_focus_replaced")

        summary = self._normalize_summary_text(
            summary=generated.summaryComment,
            strengths=strengths,
            weakness_focus=weakness_focus,
            recommendation_groups=request.recommendationGroups,
            top_majors=request.topMajors,
        )

        unique_fallbacks = sorted(set(fallback_reasons))
        return RecommendationCommentResponse(
            summaryComment=self._trim(summary, 1200),
            majorComments=comments,
            weaknessFocus=weakness_focus,
            version=self.prompt_version,
            requestId=request_id,
        ), unique_fallbacks

    def _without_competency_assessment(self, text: str) -> str:
        # Personal competency claims are rendered from scores after this free text is cleaned.
        return " ".join(
            sentence for sentence in self._split_sentences(self._sanitize_text(text))
            if not re.search(r"강점|약점|보완|취약|부족|우수|뛰어|돋보|역량.{0,20}(?:높|낮|좋|약|강)", sentence)
        )

    def _normalize_summary_text(
        self,
        summary: str | None,
        *,
        strengths: list[WeaknessField],
        weakness_focus: list[WeaknessField],
        recommendation_groups: list | None = None,
        top_majors: list | None = None,
    ) -> str:
        text = self._without_competency_assessment(summary or "")
        if not self._is_mostly_korean(text) or self._summary_is_not_rank_aligned(text, top_majors):
            text = self._rank_one_summary_fallback(top_majors)

        # Leave room for the score-derived statements even when generated prose is long.
        sentences = []
        for sentence in self._split_sentences(text)[:2]:
            if len(" ".join([*sentences, sentence])) > 850:
                break
            sentences.append(sentence)
        if not sentences or self._summary_is_not_rank_aligned(" ".join(sentences), top_majors):
            sentences = self._split_sentences(self._rank_one_summary_fallback(top_majors))[:2]
        sentences.append(competency_sentence(strengths, strength=True))
        sentences.append(competency_sentence(weakness_focus, strength=False))
        group_hint = self._summary_group_hint(recommendation_groups or [])
        if group_hint:
            sentences.append(group_hint)
        elif not any(marker in " ".join(sentences) for marker in ("공통", "선택", "비교", "기준")):
            sentences.append("전공을 선택할 때는 관심 있는 학습 주제와 준비할 영역을 함께 비교해 보세요.")
        return self._replace_banned_words(" ".join(self._ensure_period(s) for s in sentences))

    def _summary_is_not_rank_aligned(self, summary: str, top_majors: list | None) -> bool:
        rank_one = self._rank_one_major(top_majors)
        if not rank_one:
            return False
        if not self._contains_normalized(summary, rank_one.majorName):
            return True
        return self._contains_unrelated_major_family(summary, top_majors or [])

    def _rank_one_major(self, top_majors: list | None):
        if not top_majors:
            return None
        return sorted(top_majors, key=lambda item: item.rankingOrder)[0]

    def _contains_normalized(self, text: str | None, needle: str | None) -> bool:
        normalized_text = self._normalize_for_match(text)
        normalized_needle = self._normalize_for_match(needle)
        return bool(normalized_needle and normalized_needle in normalized_text)

    def _normalize_for_match(self, text: str | None) -> str:
        if not text:
            return ""
        return re.sub(r"[\W_]+", "", text.lower(), flags=re.UNICODE)

    def _contains_unrelated_major_family(self, summary: str, top_majors: list) -> bool:
        family_keywords = {
            "computer_software": (
                "컴퓨터",
                "컴공",
                "소프트웨어",
                "코딩",
                "프로그래밍",
                "개발자",
                "computer",
                "software",
                "coding",
                "programming",
                "developer",
            ),
        }
        normalized_summary = summary.lower()
        allowed_context = " ".join(self._major_context_text(major) for major in top_majors).lower()
        for keywords in family_keywords.values():
            summary_has_family = any(keyword in normalized_summary for keyword in keywords)
            context_allows_family = any(keyword in allowed_context for keyword in keywords)
            if summary_has_family and not context_allows_family:
                return True
        return False

    def _major_context_text(self, major) -> str:
        context = major.majorContext
        parts = [major.majorName, major.strengths or "", major.weaknesses or ""]
        if context:
            parts.extend(
                [
                    context.category or "",
                    context.description or "",
                    context.sourceSummary or "",
                    " ".join(context.relatedJobs or []),
                    " ".join(context.ragSnippets or []),
                ]
            )
        return " ".join(parts)

    def _rank_one_summary_fallback(self, top_majors: list | None) -> str:
        rank_one = self._rank_one_major(top_majors)
        major_name = rank_one.majorName if rank_one else "현재 1순위 전공"
        context = rank_one.majorContext if rank_one else None
        category = context.category if context and context.category else "이 분야"
        description = self._first_sentence(
            context.description if context and context.description else context.sourceSummary if context else None
        )
        field_sentence = self._major_description_sentence(
            major_name=major_name,
            description=description,
            category=category,
        )
        return f"현재 1순위 추천 전공은 {major_name}입니다. " + field_sentence

    def _major_description_sentence(self, *, major_name: str, description: str, category: str) -> str:
        if description:
            normalized = re.sub(r"이다[.!?]?$", "입니다.", description.strip())
            normalized = self._ensure_period(normalized)
            if major_name in normalized:
                return normalized + " "
            return f"{major_name}{self._topic_particle(major_name)} {normalized} "
        return f"{major_name}{self._topic_particle(major_name)} {category} 분야의 학습 방향과 연결되는 전공입니다. "

    def _topic_particle(self, text: str) -> str:
        if not text:
            return "은"
        last = text[-1]
        code = ord(last)
        if 0xAC00 <= code <= 0xD7A3:
            return "은" if (code - 0xAC00) % 28 else "는"
        return "은"

    def _first_sentence(self, text: str | None) -> str:
        cleaned = self._sanitize_text(text or "")
        if not cleaned:
            return ""
        sentences = self._split_sentences(cleaned)
        return sentences[0] if sentences else cleaned

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

    def _normalize_reason_text(
        self,
        text: str,
        *,
        major_name: str,
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
            sentences.append(f"전공의 학습 방식을 확인하기 위해 {action}을 권장합니다.")
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
        for key, label in AXIS_DISPLAY_NAMES.items():
            out = re.sub(re.escape(key), label, out, flags=re.IGNORECASE)
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
        strengths, weakness_focus = competency_feedback(request.profile)
        group_context = self._group_context_by_major(request)

        major_comments: list[MajorComment] = []
        for item in sorted(request.topMajors, key=lambda x: x.rankingOrder):
            group_item_context = group_context.get(item.majorName, {})
            reason = self._normalize_reason_text(
                f"{item.majorName} 전공의 적합도는 {item.fitScore:.1f}점입니다.",
                major_name=item.majorName,
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
                    strengths=competency_sentence(strengths, strength=True),
                    weaknesses=competency_sentence(weakness_focus, strength=False),
                    recommendationReason=self._trim(reason, 800),
                )
            )

        summary = self._normalize_summary_text(
            None,
            strengths=strengths,
            weakness_focus=weakness_focus,
            recommendation_groups=request.recommendationGroups,
            top_majors=request.topMajors,
        )

        return RecommendationCommentResponse(
            summaryComment=self._trim(summary, 1200),
            majorComments=major_comments,
            weaknessFocus=weakness_focus,
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
