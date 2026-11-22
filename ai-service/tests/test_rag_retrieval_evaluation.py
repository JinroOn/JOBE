from __future__ import annotations

from scripts.evaluate_rag_retrieval import count_duplicate_snippets, evaluate_result, grade_result
from app.rag.schemas import RagSnippet, RetrievalMeta, RetrievalResult, RetrievedMajorContext


def build_result(*, snippet_major_names: list[str], fallback_used: bool = False) -> RetrievalResult:
    snippets = [
        RagSnippet(
            chunkId=index + 1,
            documentId=1,
            majorName=major_name,
            chunkText=f"{major_name} snippet {index}",
            chunkType="service_summary",
            sourceType="service_json",
            score=1.0 - (index * 0.1),
            matchedBy="exact",
            metadata={},
        )
        for index, major_name in enumerate(snippet_major_names)
    ]
    return RetrievalResult(
        majorContext=RetrievedMajorContext(majorName=snippet_major_names[0] if snippet_major_names else "소프트웨어학과"),
        ragSnippets=snippets,
        retrievalMeta=RetrievalMeta(
            matchedBy="exact" if snippets else "none",
            topK=5,
            scoreThreshold=0.3,
            fallbackUsed=fallback_used,
            datasetVersion="test-v1",
            matchedChunkIds=[snippet.chunkId for snippet in snippets],
            latencyMs=12,
        ),
    )


def test_grade_result_policy() -> None:
    assert grade_result(related_top3=3, wrong_major_count=0, duplicate_count=0, fallback_used=False, no_result=False) == "A"
    assert grade_result(related_top3=1, wrong_major_count=0, duplicate_count=0, fallback_used=False, no_result=False) == "B"
    assert grade_result(related_top3=0, wrong_major_count=0, duplicate_count=0, fallback_used=True, no_result=False) == "C"
    assert grade_result(related_top3=2, wrong_major_count=1, duplicate_count=0, fallback_used=False, no_result=False) == "D"


def test_evaluate_result_counts_wrong_major_snippets() -> None:
    result = build_result(snippet_major_names=["소프트웨어학과", "컴퓨터공학과", "소프트웨어학과"])

    row = evaluate_result("소프트웨어학과", result)

    assert row.grade == "D"
    assert row.related_top3 == 2
    assert row.wrong_major_count == 1
    assert "wrong_major_snippet" in row.notes


def test_evaluate_result_marks_no_result_as_c() -> None:
    result = build_result(snippet_major_names=[], fallback_used=True)

    row = evaluate_result("소프트웨어학과", result)

    assert row.grade == "C"
    assert row.no_result is True
    assert row.fallback_used is True


def test_duplicate_snippet_counter() -> None:
    snippets = ["same text", "same   text", "different text"]

    assert count_duplicate_snippets(snippets) == 1
