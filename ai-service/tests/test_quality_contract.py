from __future__ import annotations

import asyncio
import json
import re
from pathlib import Path

from app.chain import GeneratedMajorComment, GeneratedRecommendation, RecommendationChain
from app.models import RecommendationCommentRequest

SAMPLES_DIR = Path(__file__).resolve().parent / "samples"
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
    "softwareImplementationScore",
    "mathLogicalScore",
    "systemUnderstandingScore",
    "communicationScore",
)
DIFFERENTIATION_MARKERS = (
    "반면",
    "다른 추천 전공과 비교하면",
    "이 전공은 특히",
    "선택 기준은",
    "보다",
    "중심",
)
SUMMARY_MARKERS = ("공통", "선택", "기준", "비교")
ACTION_MARKERS = ("보완", "연습", "훈련", "개선", "시도", "정리", "학습", "프로젝트", "활동")


def load_request(name: str) -> RecommendationCommentRequest:
    content = (SAMPLES_DIR / f"{name}.json").read_text(encoding="utf-8")
    return RecommendationCommentRequest.model_validate(json.loads(content))


def test_recommendation_system_prompt_treats_braces_as_literal_text() -> None:
    chain = RecommendationChain()
    chain.prompt_text = 'Do not write "{majorName} is repeated".'

    messages = chain._build_prompt().format_messages(input_json='{"ok": true}')  # noqa: SLF001

    assert messages[0].content == 'Do not write "{majorName} is repeated".'
    assert messages[1].content == 'Input JSON:\n{"ok": true}'


def sentence_count(text: str) -> int:
    return len([s for s in re.split(r"(?<=[.!?])\s+", text.strip()) if s.strip()])


def korean_ratio(text: str) -> float:
    hangul = len(re.findall(r"[가-힣]", text))
    alnum = len(re.findall(r"[A-Za-z0-9가-힣]", text))
    if alnum == 0:
        return 0.0
    return hangul / alnum


def normalize_for_similarity(text: str, major_names: list[str]) -> set[str]:
    normalized = text
    for major_name in major_names:
        normalized = normalized.replace(major_name, "전공")
    normalized = re.sub(r"[^0-9A-Za-z가-힣\s]", " ", normalized)
    normalized = re.sub(r"\s+", " ", normalized).strip().lower()
    stopwords = {"전공", "현재", "역량", "추천", "비교", "합니다", "좋습니다", "관련"}
    return {token for token in normalized.split() if len(token) > 1 and token not in stopwords}


def jaccard_similarity(left: set[str], right: set[str]) -> float:
    if not left and not right:
        return 1.0
    return len(left & right) / len(left | right)


def action_sentences(text: str) -> list[str]:
    sentences = [s.strip() for s in re.split(r"(?<=[.!?])\s+", text.strip()) if s.strip()]
    return [s for s in sentences if any(marker in s for marker in ACTION_MARKERS)]


def assert_quality(response) -> None:
    assert response.summaryComment
    assert 3 <= sentence_count(response.summaryComment) <= 5
    assert len(response.summaryComment) <= 1200
    assert korean_ratio(response.summaryComment) >= 0.30
    assert not any(b in response.summaryComment for b in BANNED_WORDS)
    assert not any(term in response.summaryComment for term in INTERNAL_TERMS)
    assert any(marker in response.summaryComment for marker in SUMMARY_MARKERS)
    assert not response.summaryComment.strip().endswith("추천합니다.")

    assert 1 <= len(response.majorComments) <= 5
    major_names = [comment.majorName for comment in response.majorComments]
    normalized_reasons: list[set[str]] = []
    remedial_actions: set[str] = set()
    for comment in response.majorComments:
        assert comment.recommendationReason
        assert 2 <= sentence_count(comment.recommendationReason) <= 3
        assert len(comment.recommendationReason) <= 800
        assert korean_ratio(comment.recommendationReason) >= 0.30
        assert not any(b in comment.recommendationReason for b in BANNED_WORDS)
        assert not any(term in comment.recommendationReason for term in INTERNAL_TERMS)
        assert any(marker in comment.recommendationReason for marker in DIFFERENTIATION_MARKERS)
        normalized_reasons.append(normalize_for_similarity(comment.recommendationReason, major_names))
        remedial_actions.update(action_sentences(comment.recommendationReason))

        assert comment.strengths is not None and comment.strengths.strip()
        assert len(comment.strengths) <= 500
        assert sentence_count(comment.strengths) >= 1
        assert not any(term in comment.strengths for term in INTERNAL_TERMS)

        assert comment.weaknesses is not None and comment.weaknesses.strip()
        assert len(comment.weaknesses) <= 500
        assert sentence_count(comment.weaknesses) >= 1
        assert not any(term in comment.weaknesses for term in INTERNAL_TERMS)

    for i, left in enumerate(normalized_reasons):
        for right in normalized_reasons[i + 1 :]:
            assert jaccard_similarity(left, right) < 0.85
    if len(response.majorComments) >= 2:
        assert len(remedial_actions) >= 2


def test_quality_rules_mock_samples() -> None:
    chain = RecommendationChain()
    for name in ("normal-high", "normal-mixed", "low-scores"):
        req = load_request(name)
        response = chain._mock_response(request=req, request_id=f"mock-{name}")  # noqa: SLF001
        assert_quality(response)


def test_generate_with_meta_falls_back_when_llm_provider_is_unavailable() -> None:
    chain = RecommendationChain()
    chain.use_mock = False
    chain.provider = "unsupported"
    req = load_request("normal-high")

    result = asyncio.run(chain.generate_with_meta(request=req, request_id="provider-fallback-test"))

    assert result.fallback_reasons == ["unsupported_provider_fallback"]
    assert req.topMajors[0].majorName in result.response.summaryComment
    assert_quality(result.response)


def test_partial_response_is_safely_completed() -> None:
    chain = RecommendationChain()
    req = load_request("partial-response")
    generated = GeneratedRecommendation(
        summaryComment="강점은 있습니다. 무조건 성공합니다.",
        majorComments=[
            GeneratedMajorComment(
                majorName=req.topMajors[0].majorName,
                rankingOrder=req.topMajors[0].rankingOrder,
                fitScore=req.topMajors[0].fitScore,
                strengths=None,
                weaknesses=None,
                recommendationReason="추천합니다.",
            )
        ],
        weaknessFocus=["invalidKey", "communicationScore"],
    )

    response, fallback_reasons = chain._normalize_generated(  # noqa: SLF001
        request=req,
        generated=generated,
        request_id="partial-test",
    )

    assert len(response.majorComments) == len(req.topMajors)
    assert "missing_major_comment" in fallback_reasons
    assert response.weaknessFocus == ["communicationScore"]
    assert_quality(response)


def test_noisy_text_is_sanitized() -> None:
    chain = RecommendationChain()
    req = load_request("noisy-input")
    generated = GeneratedRecommendation(
        summaryComment="### 반드시 합격!!! @@ 약점 보완 필요",
        majorComments=[
            GeneratedMajorComment(
                majorName=item.majorName,
                rankingOrder=item.rankingOrder,
                fitScore=item.fitScore,
                strengths="mathLogical, softwareImplementation",
                weaknesses="communication, systemUnderstanding",
                recommendationReason="무조건 잘 맞음!!!",
            )
            for item in req.topMajors
        ],
        weaknessFocus=["communicationScore", "systemUnderstandingScore"],
    )

    response, _ = chain._normalize_generated(  # noqa: SLF001
        request=req,
        generated=generated,
        request_id="noisy-test",
    )

    assert_quality(response)


def test_internal_terms_are_removed_from_generated_recommendation() -> None:
    chain = RecommendationChain()
    req = load_request("normal-high")
    generated = GeneratedRecommendation(
        summaryComment="rag 데이터와 snippet을 보면 공통 기준이 있습니다. 내부 데이터가 좋습니다. 선택 기준을 확인하세요.",
        majorComments=[
            GeneratedMajorComment(
                majorName=item.majorName,
                rankingOrder=item.rankingOrder,
                fitScore=item.fitScore,
                strengths="RAG 기준 강점입니다.",
                weaknesses="스니펫 기준 보완점입니다.",
                recommendationReason=(
                    f"{item.majorName}은 majorContext 근거와 잘 맞습니다. "
                    "다른 추천 전공과 비교하면 ragSnippets 내용이 차이를 보여줍니다."
                ),
            )
            for item in req.topMajors
        ],
        weaknessFocus=["communicationScore"],
    )

    response, _ = chain._normalize_generated(  # noqa: SLF001
        request=req,
        generated=generated,
        request_id="internal-term-test",
    )

    assert_quality(response)


def test_reason_without_difference_marker_is_completed_with_comparison() -> None:
    chain = RecommendationChain()
    req = load_request("normal-high")
    generated = GeneratedRecommendation(
        summaryComment="세 전공은 공통 역량이 맞습니다. 사용자의 강점이 잘 드러납니다. 선택 기준을 확인하면 좋습니다.",
        majorComments=[
            GeneratedMajorComment(
                majorName=item.majorName,
                rankingOrder=item.rankingOrder,
                fitScore=item.fitScore,
                strengths=item.strengths,
                weaknesses=item.weaknesses,
                recommendationReason=f"{item.majorName}은 현재 점수와 잘 맞습니다. 관련 활동을 해보면 좋습니다.",
            )
            for item in req.topMajors
        ],
        weaknessFocus=["communicationScore"],
    )

    response, _ = chain._normalize_generated(  # noqa: SLF001
        request=req,
        generated=generated,
        request_id="comparison-test",
    )

    assert_quality(response)
    for comment in response.majorComments:
        assert any(marker in comment.recommendationReason for marker in DIFFERENTIATION_MARKERS)


def test_request_accepts_optional_recommendation_groups() -> None:
    raw = json.loads((SAMPLES_DIR / "normal-high.json").read_text(encoding="utf-8"))
    old_request = RecommendationCommentRequest.model_validate(raw)
    assert old_request.recommendationGroups == []

    major_names = [item["majorName"] for item in raw["topMajors"]]
    raw["recommendationGroups"] = [
        {
            "groupOrder": 1,
            "representativeMajorName": major_names[0],
            "representativeRankingOrder": raw["topMajors"][0]["rankingOrder"],
            "similarMajorNames": major_names[1:],
            "commonFitAxes": ["정보기술", "구현력", "시스템이해"],
            "differencePoints": [
                {"majorName": major_name, "description": f"{major_name} 고유 비교 기준 중심"}
                for major_name in major_names
            ],
        }
    ]

    request = RecommendationCommentRequest.model_validate(raw)
    chain = RecommendationChain()
    context = chain._group_context_by_major(request)  # noqa: SLF001
    assert context[major_names[0]]["differentiation_hint"] == f"{major_names[0]} 고유 비교 기준 중심"
    assert context[major_names[0]]["comparison_majors"] == major_names[1:]

    response = chain._mock_response(request=request, request_id="group-request-test")  # noqa: SLF001
    assert_quality(response)


def test_summary_fallback_mentions_rank_one_major_when_generated_summary_omits_it() -> None:
    chain = RecommendationChain()
    req = load_request("normal-high")
    generated = GeneratedRecommendation(
        summaryComment="공통 적합 기준은 확인됩니다. 역량 보완도 필요합니다. 선택 기준을 비교하세요.",
        majorComments=[
            GeneratedMajorComment(
                majorName=req.topMajors[0].majorName,
                rankingOrder=req.topMajors[0].rankingOrder,
                fitScore=req.topMajors[0].fitScore,
                strengths=req.topMajors[0].strengths,
                weaknesses=req.topMajors[0].weaknesses,
                recommendationReason=(
                    f"{req.topMajors[0].majorName}은 현재 점수와 맞습니다. "
                    "선택 기준은 조직 운영과 발표 연습을 중심으로 비교해 정리하세요."
                ),
            ),
            GeneratedMajorComment(
                majorName=req.topMajors[1].majorName,
                rankingOrder=req.topMajors[1].rankingOrder,
                fitScore=req.topMajors[1].fitScore,
                strengths=req.topMajors[1].strengths,
                weaknesses=req.topMajors[1].weaknesses,
                recommendationReason=(
                    f"{req.topMajors[1].majorName}은 현재 점수와 맞습니다. "
                    "선택 기준은 자료 해석과 수리 개념 보완을 중심으로 비교해 정리하세요."
                ),
            ),
        ],
        weaknessFocus=["communicationScore"],
    )

    response, _ = chain._normalize_generated(  # noqa: SLF001
        request=req,
        generated=generated,
        request_id="rank-one-summary-test",
    )

    assert req.topMajors[0].majorName in response.summaryComment
    assert_quality(response)


def test_summary_fallback_replaces_unrelated_computer_family_summary() -> None:
    raw = json.loads((SAMPLES_DIR / "normal-high.json").read_text(encoding="utf-8"))
    raw["topMajors"][0]["majorName"] = "Business Administration"
    raw["topMajors"][1]["majorName"] = "Economics"
    raw["topMajors"][0]["strengths"] = "management fit"
    raw["topMajors"][1]["strengths"] = "economics fit"
    raw["topMajors"][0]["weaknesses"] = "communication practice"
    raw["topMajors"][1]["weaknesses"] = "presentation practice"
    req = RecommendationCommentRequest.model_validate(raw)
    chain = RecommendationChain()
    generated = GeneratedRecommendation(
        summaryComment=(
            "Business Administration이 1순위입니다. "
            "하지만 주요 추천 방향은 컴퓨터공학과 소프트웨어 개발입니다. "
            "코딩 프로젝트를 중심으로 선택 기준을 정리하세요."
        ),
        majorComments=[
            GeneratedMajorComment(
                majorName=item.majorName,
                rankingOrder=item.rankingOrder,
                fitScore=item.fitScore,
                strengths=item.strengths,
                weaknesses=item.weaknesses,
                recommendationReason=f"{item.majorName}은 현재 점수와 맞습니다. 선택 기준은 다른 전공과 비교해 정리하세요.",
            )
            for item in req.topMajors
        ],
        weaknessFocus=["communicationScore"],
    )

    response, _ = chain._normalize_generated(  # noqa: SLF001
        request=req,
        generated=generated,
        request_id="unrelated-family-summary-test",
    )

    assert "Business Administration" in response.summaryComment
    assert "소프트웨어" not in response.summaryComment
    assert "코딩" not in response.summaryComment
    assert "퀴즈 기반 역량 진단" not in response.summaryComment
    assert "보조 신호" not in response.summaryComment
    assert "communicationScore" not in response.summaryComment
    assert 3 <= sentence_count(response.summaryComment) <= 5


def test_rank_one_fallback_uses_complete_major_description_naturally() -> None:
    raw = json.loads((SAMPLES_DIR / "normal-high.json").read_text(encoding="utf-8"))
    raw["topMajors"] = [raw["topMajors"][0]]
    raw["topMajors"][0]["majorName"] = "심리학과"
    raw["topMajors"][0]["majorContext"] = {
        "category": "인문사회계열",
        "description": "심리학과는 심리와 관련된 핵심 개념과 방법을 배우고, 이를 실제 사례와 문제 해결에 적용하는 전공이다.",
        "sourceSummary": None,
        "relatedJobs": ["상담전문가", "연구원"],
        "ragSnippets": [],
    }
    req = RecommendationCommentRequest.model_validate(raw)
    chain = RecommendationChain()

    summary = chain._rank_one_summary_fallback(req.topMajors, ["communicationScore"])  # noqa: SLF001

    assert "심리학과은" not in summary
    assert "심리학과는 심리학과는" not in summary
    assert "와 연결되는 전공입니다" not in summary
    assert "심리학과는 심리와 관련된 핵심 개념과 방법을 배우고" in summary
    assert "전공입니다" in summary


def test_prompt_requires_rank_one_major_and_quiz_primary_context() -> None:
    chain = RecommendationChain()

    assert "topMajors[0]" in chain.prompt_text
    assert "1순위 추천 전공" in chain.prompt_text
    assert "채점 구조를 그대로 설명하지 않습니다" in chain.prompt_text
    assert "어떤 점에서 이 전공과 잘 맞는지" not in chain.prompt_text
    assert "전공의 학습 주제" in chain.prompt_text
    assert "사용자의 현재 강점" in chain.prompt_text
    assert "내부 필드명" in chain.prompt_text
    assert "softwareImplementationScore" in chain.prompt_text
    assert "TOP3" not in chain.prompt_text
