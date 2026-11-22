from __future__ import annotations

import os
from typing import Callable

from app.models import (
    MajorContext,
    RecommendationCommentRequest,
    TopMajor,
    WeeklyPlanRequest,
    WeeklyPlanTargetMajor,
)
from app.rag.retrieval import retrieve_major_context
from app.rag.schemas import RetrievalRequest, RetrievalResult

RetrieverFunc = Callable[[RetrievalRequest], RetrievalResult]

MAX_RAG_SNIPPETS = 6
MAX_SNIPPET_CHARS = 700


def enrich_recommendation_request_with_rag(
    request: RecommendationCommentRequest,
    *,
    request_id: str,
    retriever: RetrieverFunc = retrieve_major_context,
) -> tuple[RecommendationCommentRequest, list[str]]:
    if not _retrieval_enabled():
        return request, ["rag_retrieval_disabled"]

    fallback_reasons: list[str] = []
    top_majors: list[TopMajor] = []
    top_major_names = [major.majorName for major in request.topMajors]
    recommendation_groups = [group.model_dump(mode="json") for group in request.recommendationGroups]
    profile = request.profile.model_dump(mode="json")

    for major in request.topMajors:
        try:
            result = retriever(
                RetrievalRequest(
                    majorName=major.majorName,
                    weaknessFocus=_split_focus_text(major.weaknesses),
                    competencyProfile=profile,
                    recommendationGroups=recommendation_groups,
                    topMajors=top_major_names,
                    requestId=request_id,
                    topK=_top_k(),
                    scoreThreshold=_score_threshold(),
                    maxSnippetChars=MAX_SNIPPET_CHARS,
                    logRetrieval=_log_retrieval_enabled(),
                    enableVectorSearch=_vector_search_enabled(),
                )
            )
            merged_context = merge_major_context(major.majorContext, result)
            if result.retrievalMeta.fallbackUsed:
                fallback_reasons.append(f"rag_fallback:{major.majorName}")
        except Exception:  # noqa: BLE001
            merged_context = major.majorContext
            fallback_reasons.append(f"rag_retrieval_failed:{major.majorName}")
        top_majors.append(major.model_copy(update={"majorContext": merged_context}))

    return request.model_copy(update={"topMajors": top_majors}), sorted(set(fallback_reasons))


def enrich_weekly_plan_request_with_rag(
    request: WeeklyPlanRequest,
    *,
    request_id: str,
    retriever: RetrieverFunc = retrieve_major_context,
) -> tuple[WeeklyPlanRequest, list[str]]:
    if not _retrieval_enabled():
        return request, ["rag_retrieval_disabled"]

    try:
        result = retriever(
            RetrievalRequest(
                majorName=request.targetMajor.majorName,
                weaknessFocus=list(request.weaknessFocus),
                competencyProfile=request.profile.model_dump(mode="json"),
                topMajors=[request.targetMajor.majorName],
                requestId=request_id,
                topK=_top_k(),
                scoreThreshold=_score_threshold(),
                maxSnippetChars=MAX_SNIPPET_CHARS,
                logRetrieval=_log_retrieval_enabled(),
                enableVectorSearch=_vector_search_enabled(),
            )
        )
        merged_context = merge_major_context(request.targetMajor.majorContext, result)
        fallback_reasons = [f"rag_fallback:{request.targetMajor.majorName}"] if result.retrievalMeta.fallbackUsed else []
    except Exception:  # noqa: BLE001
        merged_context = request.targetMajor.majorContext
        fallback_reasons = [f"rag_retrieval_failed:{request.targetMajor.majorName}"]

    target_major = request.targetMajor.model_copy(update={"majorContext": merged_context})
    return request.model_copy(update={"targetMajor": target_major}), fallback_reasons


def merge_major_context(existing: MajorContext | None, result: RetrievalResult) -> MajorContext | None:
    retrieved = result.majorContext
    if result.retrievalMeta.fallbackUsed:
        return existing

    existing_snippets = existing.ragSnippets if existing else []
    retrieved_snippets = [_format_snippet(snippet) for snippet in result.ragSnippets]
    merged_snippets = _dedupe_snippets([*retrieved_snippets, *existing_snippets])[:MAX_RAG_SNIPPETS]

    return MajorContext(
        category=_first_non_empty(retrieved.category, existing.category if existing else None, max_len=30),
        description=_first_non_empty(retrieved.description, existing.description if existing else None, max_len=1000),
        sourceSummary=_first_non_empty(retrieved.sourceSummary, existing.sourceSummary if existing else None, max_len=700),
        relatedJobs=_merge_related_jobs(retrieved.relatedJobs, existing.relatedJobs if existing else []),
        ragSnippets=merged_snippets,
    )


def _format_snippet(snippet) -> str:
    parts = [
        "전공 참고 정보",
        f"출처={snippet.sourceType}",
        f"매칭={snippet.matchedBy}",
        f"내용={snippet.chunkText}",
    ]
    return _trim(" | ".join(part for part in parts if part), MAX_SNIPPET_CHARS)


def _dedupe_snippets(snippets: list[str]) -> list[str]:
    seen: set[str] = set()
    unique: list[str] = []
    for snippet in snippets:
        text = _trim(" ".join((snippet or "").split()), MAX_SNIPPET_CHARS)
        if not text:
            continue
        key = text[:120].lower()
        if key in seen:
            continue
        seen.add(key)
        unique.append(text)
    return unique


def _merge_related_jobs(primary: list[str], secondary: list[str]) -> list[str]:
    jobs: list[str] = []
    seen: set[str] = set()
    for item in [*primary, *secondary]:
        text = str(item).strip()
        if not text or text in seen:
            continue
        seen.add(text)
        jobs.append(_trim(text, 120))
        if len(jobs) >= 8:
            break
    return jobs


def _split_focus_text(text: str | None) -> list[str]:
    if not text:
        return []
    return [part.strip() for part in text.replace(";", ",").split(",") if part.strip()][:5]


def _first_non_empty(*values: str | None, max_len: int) -> str | None:
    for value in values:
        if value and value.strip():
            return _trim(value.strip(), max_len)
    return None


def _trim(text: str, max_len: int) -> str:
    return text if len(text) <= max_len else text[: max_len - 3].rstrip() + "..."


def _truthy(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on"}


def _retrieval_enabled() -> bool:
    return _truthy("AI_RAG_RETRIEVAL_ENABLED", True)


def _vector_search_enabled() -> bool:
    return _truthy("AI_RAG_VECTOR_SEARCH_ENABLED", False)


def _log_retrieval_enabled() -> bool:
    return _truthy("AI_RAG_LOG_RETRIEVAL", False)


def _top_k() -> int:
    return max(1, min(10, _env_int("AI_RAG_TOP_K", 5)))


def _score_threshold() -> float:
    return max(0.0, min(1.0, float(os.getenv("AI_RAG_SCORE_THRESHOLD", "0.3"))))


def _env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None or value.strip() == "":
        return default
    return int(value)
