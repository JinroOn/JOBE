from __future__ import annotations

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


def test_prompt_requires_common_basis_and_major_differences() -> None:
    chain = RecommendationChain()

    assert "공통 적합 근거" in chain.prompt_text
    assert "전공별 차별점" in chain.prompt_text
    assert "선택 기준" in chain.prompt_text
    assert "전공 이름만 바꾼 반복 문장" in chain.prompt_text
    assert "내부 용어 노출 금지" in chain.prompt_text
    assert "RAG" in chain.prompt_text
    assert "snippet" in chain.prompt_text
