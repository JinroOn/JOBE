from __future__ import annotations

import pytest

from app.rag import retrieval
from app.rag.models import AiMajorChunk, AiMajorDocument
from app.rag.retrieval import (
    ChunkCandidate,
    MajorRagRetriever,
    _empty_result,
    _save_retrieval_log,
    _vector_candidates,
    build_query_text,
    rank_candidates,
)
from app.rag.schemas import RetrievalRequest


class FakeEmbeddingProvider:
    def __init__(self, vector: list[float] | None = None) -> None:
        self.vector = vector or [0.1] * 1536

    def embed_texts(self, texts: list[str]) -> list[list[float]]:
        return [self.vector for _ in texts]


class CapturingSession:
    def __init__(self) -> None:
        self.added = []

    def add(self, value) -> None:
        self.added.append(value)


def make_candidate(
    *,
    chunk_id: int,
    matched_by: str,
    chunk_text: str,
    source_type: str = "service_json",
    chunk_type: str = "service_summary",
    score: float | None = 1.0,
) -> ChunkCandidate:
    document = AiMajorDocument(
        id=chunk_id,
        major_key="software",
        major_name="Software Engineering",
        standard_major_name="Software Engineering",
        source_type=source_type,
        source_path=f"datasets/{chunk_id}.json",
        dataset_version="test-v1",
        content_hash=f"doc-{chunk_id}",
        metadata_json={
            "category": "engineering",
            "relatedJobs": ["Backend Developer"],
            "coreAxes": {"primary": ["softwareImplementationScore"]},
        },
    )
    chunk = AiMajorChunk(
        id=chunk_id,
        document_id=chunk_id,
        major_key="software",
        major_name="Software Engineering",
        chunk_index=chunk_id,
        chunk_text=chunk_text,
        chunk_type=chunk_type,
        source_type=source_type,
        token_count=10,
        content_hash=f"chunk-{chunk_id}",
        embedding_model="gemini-embedding-2",
        embedding=[0.1] * 1536,
        metadata_json={"sourcePath": f"datasets/{chunk_id}.json"},
    )
    return ChunkCandidate(chunk=chunk, document=document, matched_by=matched_by, score=score)


def test_build_query_text_excludes_sensitive_profile_keys() -> None:
    request = RetrievalRequest(
        majorName="Software Engineering",
        weaknessFocus=["softwareImplementationScore"],
        competencyProfile={"mathLogicalScore": 80, "api_key": "secret-value"},
        topMajors=["Software Engineering"],
    )

    query_text = build_query_text(request)

    assert "Software Engineering" in query_text
    assert "mathLogicalScore=80" in query_text
    assert "secret-value" not in query_text
    assert "api_key" not in query_text


def test_rank_candidates_prioritizes_exact_and_deduplicates() -> None:
    candidates = [
        make_candidate(chunk_id=1, matched_by="vector", chunk_text="same text", source_type="rag_jsonl", score=0.9),
        make_candidate(chunk_id=2, matched_by="exact", chunk_text="same text", source_type="service_json", score=1.0),
        make_candidate(chunk_id=3, matched_by="alias", chunk_text="different text", source_type="service_json", score=1.0),
    ]

    ranked = rank_candidates(candidates, top_k=5, max_snippet_chars=1000)

    assert [candidate.chunk.id for candidate in ranked] == [2, 3]


def test_retriever_returns_exact_context_without_vector(monkeypatch) -> None:
    exact_candidate = make_candidate(chunk_id=10, matched_by="exact", chunk_text="summary text")

    monkeypatch.setattr(retrieval, "_latest_dataset_version", lambda session: "test-v1")
    monkeypatch.setattr(retrieval, "_find_alias", lambda session, major_key: None)
    monkeypatch.setattr(retrieval, "_exact_candidates", lambda *args, **kwargs: [exact_candidate])
    monkeypatch.setattr(retrieval, "_vector_candidates", lambda *args, **kwargs: [])

    result = MajorRagRetriever(session=object()).retrieve(RetrievalRequest(majorName="Software Engineering"))

    assert result.retrievalMeta.matchedBy == "exact"
    assert result.retrievalMeta.fallbackUsed is False
    assert result.retrievalMeta.datasetVersion == "test-v1"
    assert result.majorContext.ragSnippets == ["summary text"]


def test_retriever_returns_fallback_meta_when_no_results(monkeypatch) -> None:
    monkeypatch.setattr(retrieval, "_latest_dataset_version", lambda session: "test-v1")
    monkeypatch.setattr(retrieval, "_find_alias", lambda session, major_key: None)
    monkeypatch.setattr(retrieval, "_exact_candidates", lambda *args, **kwargs: [])
    monkeypatch.setattr(retrieval, "_vector_candidates", lambda *args, **kwargs: [])

    result = MajorRagRetriever(session=object()).retrieve(RetrievalRequest(majorName="Unknown Major"))

    assert result.retrievalMeta.matchedBy == "none"
    assert result.retrievalMeta.fallbackUsed is True
    assert result.retrievalMeta.failureReason == "NO_RAG_RESULTS"
    assert result.majorContext.ragSnippets == []


def test_query_embedding_dimension_mismatch_is_rejected() -> None:
    request = RetrievalRequest(majorName="Software Engineering")

    with pytest.raises(ValueError, match="query embedding dimension mismatch"):
        _vector_candidates(
            object(),
            request,
            dataset_version="test-v1",
            limit=5,
            embedding_provider=FakeEmbeddingProvider([0.1, 0.2]),
        )


def test_retrieval_log_stores_query_hash_not_raw_text() -> None:
    session = CapturingSession()
    request = RetrievalRequest(
        majorName="Software Engineering",
        competencyProfile={"api_key": "secret-value", "mathLogicalScore": 90},
        requestId="req-1",
        topK=3,
    )
    result = _empty_result(
        request,
        started_at=0.0,
        matched_by="fallback",
        fallback_used=True,
        failure_reason="NO_RAG_RESULTS",
    )

    _save_retrieval_log(session, request, result)

    assert len(session.added) == 1
    log = session.added[0]
    assert log.request_id == "req-1"
    assert log.query_text_hash is not None
    assert "secret-value" not in log.query_text_hash
    assert log.major_name == "Software Engineering"
    assert log.fallback_used is True
