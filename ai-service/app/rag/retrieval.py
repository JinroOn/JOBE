from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any

from sqlalchemy import Select, and_, create_engine, desc, func, or_, select
from sqlalchemy.orm import Session

from app.db import get_ai_database_settings
from app.rag.ingestion import create_embedding_provider, get_embedding_dimension, normalize_major_key, stable_hash
from app.rag.models import AiMajorAlias, AiMajorChunk, AiMajorDocument, AiRetrievalLog
from app.rag.schemas import (
    RagSnippet,
    RetrievalMeta,
    RetrievalRequest,
    RetrievalResult,
    RetrievedMajorContext,
)

SOURCE_PRIORITY = {
    "service_json": 0,
    "rag_jsonl": 1,
    "manual": 2,
    "public_data": 3,
}

CHUNK_TYPE_PRIORITY = {
    "service_summary": 0,
    "description": 1,
    "major_overview": 2,
    "core_axes": 3,
    "related_jobs": 4,
    "differentiation": 5,
    "keywords": 6,
}

DEFAULT_EMBEDDING_MODEL = "text-embedding-3-large"


@dataclass(frozen=True)
class ChunkCandidate:
    chunk: AiMajorChunk
    document: AiMajorDocument
    matched_by: str
    score: float | None = None
    alias_name: str | None = None
    canonical_major_name: str | None = None


@dataclass(frozen=True)
class AliasMatch:
    alias_name: str
    major_key: str
    major_name: str
    confidence: float


class MajorRagRetriever:
    def __init__(self, *, session: Session | None = None, embedding_provider: Any | None = None) -> None:
        self._session = session
        self._embedding_provider = embedding_provider

    def retrieve(self, request: RetrievalRequest) -> RetrievalResult:
        started_at = time.perf_counter()
        owns_session = self._session is None
        session = self._session
        if session is None:
            engine = create_engine(get_ai_database_settings().sqlalchemy_url, future=True)
            session = Session(engine)

        try:
            result = self._retrieve_with_session(session, request, started_at)
            if request.logRetrieval:
                _save_retrieval_log(session, request, result)
                if owns_session:
                    session.commit()
            return result
        except ValueError:
            raise
        except Exception as exc:  # noqa: BLE001
            result = _empty_result(
                request,
                started_at=started_at,
                matched_by="fallback",
                fallback_used=True,
                failure_reason=type(exc).__name__,
            )
            if request.logRetrieval:
                try:
                    _save_retrieval_log(session, request, result)
                    if owns_session:
                        session.commit()
                except Exception:  # noqa: BLE001
                    if owns_session:
                        session.rollback()
            return result
        finally:
            if owns_session:
                session.close()

    def _retrieve_with_session(self, session: Session, request: RetrievalRequest, started_at: float) -> RetrievalResult:
        major_key = normalize_major_key(request.majorName)
        dataset_version = request.datasetVersion or _latest_dataset_version(session)
        alias_match = _find_alias(session, major_key)
        canonical_key = alias_match.major_key if alias_match else major_key

        candidates: list[ChunkCandidate] = []
        candidates.extend(_exact_candidates(session, canonical_key, dataset_version, request.topK * 2, matched_by="exact"))
        if alias_match:
            candidates.extend(
                ChunkCandidate(
                    chunk=candidate.chunk,
                    document=candidate.document,
                    matched_by="alias",
                    score=candidate.score,
                    alias_name=alias_match.alias_name,
                    canonical_major_name=alias_match.major_name,
                )
                for candidate in _exact_candidates(session, alias_match.major_key, dataset_version, request.topK * 2, matched_by="alias")
            )

        vector_candidates: list[ChunkCandidate] = []
        if request.enableVectorSearch:
            try:
                vector_candidates = _vector_candidates(
                    session,
                    request,
                    dataset_version=dataset_version,
                    limit=request.topK * 4,
                    embedding_provider=self._embedding_provider,
                )
            except ValueError:
                raise
            except Exception:  # noqa: BLE001
                vector_candidates = []
        candidates.extend(vector_candidates)

        ranked = rank_candidates(candidates, top_k=request.topK, max_snippet_chars=request.maxSnippetChars)
        if not ranked:
            return _empty_result(
                request,
                started_at=started_at,
                dataset_version=dataset_version,
                matched_by="none",
                fallback_used=True,
                failure_reason="NO_RAG_RESULTS",
            )

        context = _build_major_context(request.majorName, ranked)
        snippets = [_to_snippet(candidate, max_chars=request.maxSnippetChars) for candidate in ranked]
        matched_by = _dominant_match(snippets)
        meta = RetrievalMeta(
            matchedBy=matched_by,
            topK=request.topK,
            scoreThreshold=request.scoreThreshold,
            fallbackUsed=False,
            datasetVersion=dataset_version,
            matchedChunkIds=[snippet.chunkId for snippet in snippets],
            latencyMs=_elapsed_ms(started_at),
            aliasName=alias_match.alias_name if alias_match else None,
            canonicalMajorName=alias_match.major_name if alias_match else None,
        )
        return RetrievalResult(majorContext=context, ragSnippets=snippets, retrievalMeta=meta)


def retrieve_major_context(request: RetrievalRequest, *, embedding_provider: Any | None = None) -> RetrievalResult:
    return MajorRagRetriever(embedding_provider=embedding_provider).retrieve(request)


def rank_candidates(candidates: list[ChunkCandidate], *, top_k: int, max_snippet_chars: int) -> list[ChunkCandidate]:
    seen_hashes: set[str] = set()
    unique: list[ChunkCandidate] = []
    for candidate in sorted(candidates, key=_ranking_key):
        text = _clip(candidate.chunk.chunk_text, max_snippet_chars)
        text_hash = stable_hash(text)
        if text_hash in seen_hashes:
            continue
        seen_hashes.add(text_hash)
        unique.append(candidate)
        if len(unique) >= top_k:
            break
    return unique


def build_query_text(request: RetrievalRequest) -> str:
    profile_items = [
        f"{key}={value}"
        for key, value in sorted(request.competencyProfile.items())
        if value is not None and key.lower() not in {"name", "email", "phone", "token", "apikey", "api_key"}
    ][:12]
    groups = []
    for group in request.recommendationGroups[:5]:
        representative = group.get("representativeMajorName") or group.get("majorName")
        common_axes = group.get("commonFitAxes") or []
        if representative:
            groups.append(f"{representative}:{','.join(str(axis) for axis in common_axes[:5])}")
    return "\n".join(
        part
        for part in [
            f"전공명: {request.majorName}",
            f"취약 역량: {', '.join(request.weaknessFocus[:5])}",
            f"추천군: {'; '.join(groups)}",
            f"상위 전공: {', '.join(request.topMajors[:5])}",
            f"역량 프로필 요약: {', '.join(profile_items)}",
        ]
        if part.strip()
    )


def _latest_dataset_version(session: Session) -> str | None:
    return session.scalar(select(AiMajorDocument.dataset_version).order_by(desc(AiMajorDocument.created_at)).limit(1))


def _find_alias(session: Session, normalized_alias: str) -> AliasMatch | None:
    alias = session.scalar(
        select(AiMajorAlias)
        .where(AiMajorAlias.normalized_alias == normalized_alias)
        .order_by(desc(AiMajorAlias.confidence))
        .limit(1)
    )
    if alias is None:
        return None
    return AliasMatch(
        alias_name=alias.alias_name,
        major_key=alias.major_key,
        major_name=alias.major_name,
        confidence=alias.confidence,
    )


def _exact_candidates(
    session: Session,
    major_key: str,
    dataset_version: str | None,
    limit: int,
    *,
    matched_by: str,
) -> list[ChunkCandidate]:
    stmt = (
        _base_chunk_select()
        .where(or_(AiMajorDocument.major_key == major_key, AiMajorChunk.major_key == major_key))
        .order_by(
            func.coalesce(AiMajorChunk.metadata_json["sourcePriority"].as_integer(), 99),
            AiMajorChunk.source_type,
            AiMajorChunk.chunk_index,
        )
        .limit(limit)
    )
    stmt = _with_dataset_version(stmt, dataset_version)
    return [ChunkCandidate(chunk=chunk, document=document, matched_by=matched_by, score=1.0) for chunk, document in session.execute(stmt)]


def _vector_candidates(
    session: Session,
    request: RetrievalRequest,
    *,
    dataset_version: str | None,
    limit: int,
    embedding_provider: Any | None,
) -> list[ChunkCandidate]:
    query_vector = _embed_query(request, embedding_provider)
    if query_vector is None:
        return []
    dimension = get_embedding_dimension()
    if len(query_vector) != dimension:
        raise ValueError(f"query embedding dimension mismatch: expected={dimension} actual={len(query_vector)}")

    distance = AiMajorChunk.embedding.cosine_distance(query_vector)
    stmt = (
        select(AiMajorChunk, AiMajorDocument, distance.label("distance"))
        .join(AiMajorDocument, AiMajorDocument.id == AiMajorChunk.document_id)
        .where(AiMajorChunk.embedding.is_not(None))
        .order_by(distance)
        .limit(limit)
    )
    stmt = _with_dataset_version(stmt, dataset_version)
    candidates: list[ChunkCandidate] = []
    for chunk, document, raw_distance in session.execute(stmt):
        score = max(0.0, 1.0 - float(raw_distance))
        if score < request.scoreThreshold:
            continue
        candidates.append(ChunkCandidate(chunk=chunk, document=document, matched_by="vector", score=score))
    return candidates


def _embed_query(request: RetrievalRequest, embedding_provider: Any | None) -> list[float] | None:
    embedder = embedding_provider
    if embedder is None:
        embedder = create_embedding_provider(model=_embedding_model(), dimension=get_embedding_dimension())
    vectors = embedder.embed_texts([build_query_text(request)])
    if not vectors:
        return None
    return vectors[0]


def _embedding_model() -> str:
    import os

    return os.getenv("EMBEDDING_MODEL") or DEFAULT_EMBEDDING_MODEL


def _base_chunk_select() -> Select:
    return select(AiMajorChunk, AiMajorDocument).join(AiMajorDocument, AiMajorDocument.id == AiMajorChunk.document_id)


def _with_dataset_version(stmt: Select, dataset_version: str | None) -> Select:
    if dataset_version:
        return stmt.where(AiMajorDocument.dataset_version == dataset_version)
    return stmt


def _ranking_key(candidate: ChunkCandidate) -> tuple[int, int, int, float, int]:
    match_rank = {"exact": 0, "alias": 1, "vector": 2}.get(candidate.matched_by, 9)
    source_rank = SOURCE_PRIORITY.get(candidate.chunk.source_type, 99)
    chunk_rank = CHUNK_TYPE_PRIORITY.get(candidate.chunk.chunk_type, 50)
    score_rank = -(candidate.score if candidate.score is not None else 0.0)
    text_len = len(candidate.chunk.chunk_text or "")
    return (match_rank, source_rank, chunk_rank, score_rank, text_len)


def _build_major_context(requested_major_name: str, candidates: list[ChunkCandidate]) -> RetrievedMajorContext:
    primary = candidates[0]
    document = primary.document
    metadata = _merge_metadata(candidates)
    snippets = [_clip(candidate.chunk.chunk_text, 1600) for candidate in candidates]
    source_summary = _first_chunk_text(candidates, "service_summary")
    description = _first_chunk_text(candidates, "description")
    return RetrievedMajorContext(
        majorName=document.major_name or requested_major_name,
        standardMajorName=document.standard_major_name,
        category=_as_optional_str(metadata.get("category")),
        description=description,
        sourceSummary=source_summary,
        relatedJobs=_as_str_list(metadata.get("relatedJobs")),
        coreAxes=metadata.get("coreAxes"),
        ragSnippets=snippets,
    )


def _merge_metadata(candidates: list[ChunkCandidate]) -> dict[str, Any]:
    merged: dict[str, Any] = {}
    for candidate in reversed(candidates):
        merged.update(candidate.document.metadata_json or {})
        merged.update(candidate.chunk.metadata_json or {})
    return merged


def _first_chunk_text(candidates: list[ChunkCandidate], chunk_type: str) -> str | None:
    for candidate in candidates:
        if candidate.chunk.chunk_type == chunk_type:
            return _clip(candidate.chunk.chunk_text, 1000 if chunk_type == "description" else 700)
    return None


def _to_snippet(candidate: ChunkCandidate, *, max_chars: int) -> RagSnippet:
    metadata = dict(candidate.chunk.metadata_json or {})
    if candidate.alias_name:
        metadata.update(
            {
                "aliasName": candidate.alias_name,
                "canonicalMajorName": candidate.canonical_major_name,
            }
        )
    return RagSnippet(
        chunkId=candidate.chunk.id,
        documentId=candidate.document.id,
        majorName=candidate.chunk.major_name,
        chunkText=_clip(candidate.chunk.chunk_text, max_chars),
        chunkType=candidate.chunk.chunk_type,
        sourceType=candidate.chunk.source_type,
        sourcePath=candidate.document.source_path,
        score=candidate.score,
        matchedBy=candidate.matched_by,  # type: ignore[arg-type]
        metadata=metadata,
    )


def _dominant_match(snippets: list[RagSnippet]) -> str:
    for matched_by in ("exact", "alias", "vector"):
        if any(snippet.matchedBy == matched_by for snippet in snippets):
            return matched_by
    return "none"


def _empty_result(
    request: RetrievalRequest,
    *,
    started_at: float,
    dataset_version: str | None = None,
    matched_by: str,
    fallback_used: bool,
    failure_reason: str | None,
) -> RetrievalResult:
    return RetrievalResult(
        majorContext=RetrievedMajorContext(majorName=request.majorName, ragSnippets=[]),
        ragSnippets=[],
        retrievalMeta=RetrievalMeta(
            matchedBy=matched_by,  # type: ignore[arg-type]
            topK=request.topK,
            scoreThreshold=request.scoreThreshold,
            fallbackUsed=fallback_used,
            datasetVersion=dataset_version,
            matchedChunkIds=[],
            latencyMs=_elapsed_ms(started_at),
            failureReason=failure_reason,
        ),
    )


def _save_retrieval_log(session: Session, request: RetrievalRequest, result: RetrievalResult) -> None:
    session.add(
        AiRetrievalLog(
            request_id=request.requestId,
            query_text_hash=stable_hash(build_query_text(request)),
            major_name=request.majorName,
            top_k=request.topK,
            score_threshold=request.scoreThreshold,
            matched_chunk_ids=result.retrievalMeta.matchedChunkIds,
            latency_ms=result.retrievalMeta.latencyMs,
            fallback_used=result.retrievalMeta.fallbackUsed,
        )
    )


def _elapsed_ms(started_at: float) -> int:
    return int(round((time.perf_counter() - started_at) * 1000))


def _clip(value: str, max_chars: int) -> str:
    normalized = " ".join((value or "").split())
    if len(normalized) <= max_chars:
        return normalized
    return normalized[: max_chars - 3].rstrip() + "..."


def _as_optional_str(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _as_str_list(value: Any) -> list[str]:
    if not value:
        return []
    if isinstance(value, list):
        return [str(item) for item in value if str(item).strip()][:8]
    return [str(value)]
