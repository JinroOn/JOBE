from __future__ import annotations

from app.models import (
    MajorContext,
    Profile,
    RecommendationCommentRequest,
    TopMajor,
    WeeklyPlanConstraints,
    WeeklyPlanRequest,
    WeeklyPlanTargetMajor,
)
from app.rag.context import (
    enrich_recommendation_request_with_rag,
    enrich_weekly_plan_request_with_rag,
    merge_major_context,
)
from app.rag.schemas import RagSnippet, RetrievalMeta, RetrievalRequest, RetrievalResult, RetrievedMajorContext


def build_profile() -> Profile:
    return Profile(
        mathLogicalScore=80,
        problemSolvingScore=78,
        infoTechUtilizationScore=77,
        softwareImplementationScore=82,
        systemUnderstandingScore=70,
        dataAnalysisScore=75,
        communicationScore=60,
        collaborationScore=68,
        selfManagementScore=72,
    )


def build_recommendation_request() -> RecommendationCommentRequest:
    return RecommendationCommentRequest(
        sessionId=1,
        profile=build_profile(),
        topMajors=[
            TopMajor(
                majorName="Software Engineering",
                rankingOrder=1,
                fitScore=91.0,
                strengths="implementation",
                weaknesses="communicationScore",
                majorContext=MajorContext(
                    category="engineering",
                    sourceSummary="file summary",
                    relatedJobs=["Existing Job"],
                    ragSnippets=["file snippet"],
                ),
            )
        ],
    )


def build_weekly_request() -> WeeklyPlanRequest:
    return WeeklyPlanRequest(
        sessionId=2,
        targetMajor=WeeklyPlanTargetMajor(
            majorName="Software Engineering",
            fitScore=90.0,
            majorContext=MajorContext(ragSnippets=["weekly file snippet"]),
        ),
        weaknessFocus=["communicationScore"],
        profile=build_profile(),
        constraints=WeeklyPlanConstraints(weeks=4, studyHoursPerWeek=8, preferredStyle="practice"),
    )


def build_retrieval_result(*, fallback_used: bool = False) -> RetrievalResult:
    snippets = []
    matched_ids = []
    if not fallback_used:
        snippets = [
            RagSnippet(
                chunkId=10,
                documentId=1,
                majorName="Software Engineering",
                chunkText="AI DB exact snippet",
                chunkType="service_summary",
                sourceType="service_json",
                sourcePath="majors/software.service.json",
                score=1.0,
                matchedBy="exact",
                metadata={},
            )
        ]
        matched_ids = [10]
    return RetrievalResult(
        majorContext=RetrievedMajorContext(
            majorName="Software Engineering",
            standardMajorName="Software Engineering",
            category="engineering",
            sourceSummary="AI DB summary",
            relatedJobs=["AI DB Job"],
            ragSnippets=["AI DB exact snippet"],
        ),
        ragSnippets=snippets,
        retrievalMeta=RetrievalMeta(
            matchedBy="exact" if not fallback_used else "none",
            topK=5,
            scoreThreshold=0.3,
            fallbackUsed=fallback_used,
            datasetVersion="test-v1",
            matchedChunkIds=matched_ids,
        ),
    )


def test_merge_major_context_prioritizes_ai_db_snippets() -> None:
    existing = MajorContext(sourceSummary="file summary", relatedJobs=["Existing Job"], ragSnippets=["file snippet"])
    merged = merge_major_context(existing, build_retrieval_result())

    assert merged is not None
    assert merged.sourceSummary == "AI DB summary"
    assert merged.relatedJobs == ["AI DB Job", "Existing Job"]
    assert merged.ragSnippets[0].startswith("전공 참고 정보")
    assert "AI DB exact snippet" in merged.ragSnippets[0]
    assert merged.ragSnippets[1] == "file snippet"


def test_recommendation_request_is_enriched_without_dto_shape_change(monkeypatch) -> None:
    monkeypatch.setenv("AI_RAG_RETRIEVAL_ENABLED", "true")
    monkeypatch.setenv("AI_RAG_VECTOR_SEARCH_ENABLED", "false")
    calls: list[RetrievalRequest] = []

    def fake_retriever(request: RetrievalRequest) -> RetrievalResult:
        calls.append(request)
        return build_retrieval_result()

    enriched, fallback_reasons = enrich_recommendation_request_with_rag(
        build_recommendation_request(),
        request_id="req-1",
        retriever=fake_retriever,
    )

    assert fallback_reasons == []
    assert len(calls) == 1
    assert calls[0].enableVectorSearch is False
    assert enriched.topMajors[0].majorContext is not None
    assert "AI DB exact snippet" in enriched.topMajors[0].majorContext.ragSnippets[0]
    assert enriched.model_dump()["topMajors"][0]["majorContext"]["ragSnippets"]


def test_recommendation_retrieval_failure_keeps_existing_context(monkeypatch) -> None:
    monkeypatch.setenv("AI_RAG_RETRIEVAL_ENABLED", "true")

    def failing_retriever(request: RetrievalRequest) -> RetrievalResult:
        raise RuntimeError("db unavailable")

    original = build_recommendation_request()
    enriched, fallback_reasons = enrich_recommendation_request_with_rag(
        original,
        request_id="req-2",
        retriever=failing_retriever,
    )

    assert "rag_retrieval_failed:Software Engineering" in fallback_reasons
    assert enriched.topMajors[0].majorContext == original.topMajors[0].majorContext


def test_weekly_plan_request_is_enriched(monkeypatch) -> None:
    monkeypatch.setenv("AI_RAG_RETRIEVAL_ENABLED", "true")
    monkeypatch.setenv("AI_RAG_VECTOR_SEARCH_ENABLED", "false")
    calls: list[RetrievalRequest] = []

    def fake_retriever(request: RetrievalRequest) -> RetrievalResult:
        calls.append(request)
        return build_retrieval_result()

    enriched, fallback_reasons = enrich_weekly_plan_request_with_rag(
        build_weekly_request(),
        request_id="plan-req-1",
        retriever=fake_retriever,
    )

    assert fallback_reasons == []
    assert len(calls) == 1
    assert calls[0].weaknessFocus == ["communicationScore"]
    assert enriched.targetMajor.majorContext is not None
    assert "AI DB exact snippet" in enriched.targetMajor.majorContext.ragSnippets[0]


def test_fallback_retrieval_preserves_file_based_context(monkeypatch) -> None:
    monkeypatch.setenv("AI_RAG_RETRIEVAL_ENABLED", "true")

    def fallback_retriever(request: RetrievalRequest) -> RetrievalResult:
        return build_retrieval_result(fallback_used=True)

    original = build_weekly_request()
    enriched, fallback_reasons = enrich_weekly_plan_request_with_rag(
        original,
        request_id="plan-req-2",
        retriever=fallback_retriever,
    )

    assert "rag_fallback:Software Engineering" in fallback_reasons
    assert enriched.targetMajor.majorContext == original.targetMajor.majorContext
