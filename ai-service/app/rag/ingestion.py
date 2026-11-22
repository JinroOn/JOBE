from __future__ import annotations

import hashlib
import json
import os
import re
import time
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from sqlalchemy import create_engine, delete, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import Session

from app.db import get_ai_database_settings
from app.rag.models import (
    EMBEDDING_DIMENSION,
    AiEmbeddingJob,
    AiMajorChunk,
    AiMajorDocument,
)

MIN_CHUNK_CHARS = 20
MAX_CHUNK_CHARS = 1800


@dataclass(frozen=True)
class ParsedChunk:
    major_key: str
    major_name: str
    chunk_index: int
    chunk_text: str
    chunk_type: str
    source_type: str
    content_hash: str
    metadata_json: dict[str, Any] = field(default_factory=dict)
    token_count: int | None = None
    embedding: list[float] | None = None


@dataclass(frozen=True)
class ParsedDocument:
    major_key: str
    major_name: str
    standard_major_name: str | None
    source_type: str
    source_path: str
    dataset_version: str
    content_hash: str
    metadata_json: dict[str, Any]
    chunks: list[ParsedChunk]


@dataclass(frozen=True)
class ParseError:
    source_path: str
    message: str


@dataclass(frozen=True)
class IngestionPlan:
    documents: list[ParsedDocument]
    errors: list[ParseError]

    @property
    def chunk_count(self) -> int:
        return sum(len(document.chunks) for document in self.documents)


@dataclass(frozen=True)
class IngestionResult:
    job_id: str
    status: str
    documents_seen: int
    chunks_seen: int
    documents_inserted: int
    chunks_inserted: int
    failed_count: int
    errors: list[ParseError]
    dry_run: bool


def normalize_major_key(value: str | None) -> str:
    text = (value or "").strip().lower()
    text = re.sub(r"[\s\-_]+", "", text)
    text = re.sub(r"[()\[\]{}]", "", text)
    text = re.sub(r"[^\w가-힣]", "", text)
    return text


def stable_hash(value: Any) -> str:
    if isinstance(value, str):
        payload = value
    else:
        payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def parse_dataset(dataset_root: Path, dataset_version: str, *, limit: int | None = None) -> IngestionPlan:
    errors: list[ParseError] = []
    documents: list[ParsedDocument] = []
    service_files = sorted((dataset_root / "majors").glob("*.service.json"))
    rag_files = sorted((dataset_root / "rag" / "per-major").glob("*.rag.jsonl"))

    for path in _apply_limit(service_files, limit):
        try:
            document = parse_service_json(path, dataset_root=dataset_root, dataset_version=dataset_version)
            if document.chunks:
                documents.append(document)
        except Exception as exc:  # noqa: BLE001
            errors.append(ParseError(source_path=_relative_path(path, dataset_root), message=str(exc)))

    remaining_limit = None if limit is None else max(limit - len(documents), 0)
    for path in _apply_limit(rag_files, remaining_limit):
        try:
            document, line_errors = parse_rag_jsonl(path, dataset_root=dataset_root, dataset_version=dataset_version)
            errors.extend(line_errors)
            if document.chunks:
                documents.append(document)
        except Exception as exc:  # noqa: BLE001
            errors.append(ParseError(source_path=_relative_path(path, dataset_root), message=str(exc)))

    return IngestionPlan(documents=documents, errors=errors)


def parse_service_json(path: Path, *, dataset_root: Path, dataset_version: str) -> ParsedDocument:
    data = json.loads(path.read_text(encoding="utf-8"))
    source_path = _relative_path(path, dataset_root)
    major_name = _as_text(data.get("majorName") or data.get("standardMajorName") or _major_name_from_filename(path))
    standard_major_name = _optional_text(data.get("standardMajorName")) or major_name
    major_key = normalize_major_key(standard_major_name or major_name)
    metadata = _service_metadata(data, source_path)
    content_hash = stable_hash({"source_path": source_path, "data": data})
    chunks = _build_service_chunks(
        data=data,
        major_key=major_key,
        major_name=major_name,
        source_path=source_path,
    )
    return ParsedDocument(
        major_key=major_key,
        major_name=major_name,
        standard_major_name=standard_major_name,
        source_type="service_json",
        source_path=source_path,
        dataset_version=dataset_version,
        content_hash=content_hash,
        metadata_json=metadata,
        chunks=chunks,
    )


def parse_rag_jsonl(path: Path, *, dataset_root: Path, dataset_version: str) -> tuple[ParsedDocument, list[ParseError]]:
    source_path = _relative_path(path, dataset_root)
    errors: list[ParseError] = []
    chunks: list[ParsedChunk] = []
    major_name = _major_name_from_filename(path)
    standard_major_name: str | None = major_name
    major_key = normalize_major_key(major_name)
    file_hash_parts: list[str] = []

    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            data = json.loads(line)
        except json.JSONDecodeError as exc:
            errors.append(ParseError(source_path=f"{source_path}:{line_no}", message=f"invalid jsonl line: {exc.msg}"))
            continue

        text = _first_text(data, "content", "text", "summary")
        if not _is_meaningful_chunk(text):
            continue

        major_name = _as_text(data.get("majorName") or major_name)
        standard_major_name = _optional_text(data.get("standardMajorName")) or standard_major_name
        major_key = normalize_major_key(standard_major_name or major_name)
        chunk_type = _as_text(data.get("chunkType") or data.get("sourceType") or "rag_chunk")
        metadata = _rag_metadata(data, source_path, line_no)
        for part in split_text(text):
            chunk_hash = stable_hash({"source_path": source_path, "line": line_no, "chunk_type": chunk_type, "text": part})
            chunks.append(
                ParsedChunk(
                    major_key=major_key,
                    major_name=major_name,
                    chunk_index=len(chunks),
                    chunk_text=part,
                    chunk_type=chunk_type,
                    source_type="rag_jsonl",
                    content_hash=chunk_hash,
                    metadata_json=metadata,
                    token_count=estimate_token_count(part),
                )
            )
            file_hash_parts.append(chunk_hash)

    content_hash = stable_hash({"source_path": source_path, "chunks": file_hash_parts})
    return (
        ParsedDocument(
            major_key=major_key,
            major_name=major_name,
            standard_major_name=standard_major_name,
            source_type="rag_jsonl",
            source_path=source_path,
            dataset_version=dataset_version,
            content_hash=content_hash,
            metadata_json={"sourcePath": source_path, "sourceType": "rag_jsonl"},
            chunks=chunks,
        ),
        errors,
    )


def split_text(text: str, *, max_chars: int = MAX_CHUNK_CHARS) -> list[str]:
    normalized = re.sub(r"\s+", " ", text).strip()
    if not normalized:
        return []
    if len(normalized) <= max_chars:
        return [normalized] if _is_meaningful_chunk(normalized) else []

    parts: list[str] = []
    current = ""
    sentences = re.split(r"(?<=[.!?。！？]|[.?!])\s+|(?<=[다요죠니다])\.\s*", normalized)
    for sentence in sentences:
        sentence = sentence.strip()
        if not sentence:
            continue
        if len(current) + len(sentence) + 1 <= max_chars:
            current = f"{current} {sentence}".strip()
        else:
            if _is_meaningful_chunk(current):
                parts.append(current)
            current = sentence
    if _is_meaningful_chunk(current):
        parts.append(current)
    return parts or [normalized[:max_chars]]


def estimate_token_count(text: str) -> int:
    # Korean tokenizers vary by provider; this rough count is only metadata for retrieval tuning.
    return max(1, len(text) // 3)


def ingest_dataset(
    *,
    dataset_root: Path,
    dataset_version: str,
    embedding_model: str,
    dry_run: bool,
    limit: int | None = None,
    force: bool = False,
    skip_embeddings: bool = False,
) -> IngestionResult:
    plan = parse_dataset(dataset_root=dataset_root, dataset_version=dataset_version, limit=limit)
    job_id = str(uuid.uuid4())
    if dry_run:
        return IngestionResult(
            job_id=job_id,
            status="DRY_RUN",
            documents_seen=len(plan.documents),
            chunks_seen=plan.chunk_count,
            documents_inserted=0,
            chunks_inserted=0,
            failed_count=len(plan.errors),
            errors=plan.errors,
            dry_run=True,
        )

    embedder: EmbeddingProvider
    if skip_embeddings:
        embedder = NoopEmbeddingProvider(dimension=get_embedding_dimension())
    else:
        embedder = create_embedding_provider(model=embedding_model, dimension=get_embedding_dimension())

    documents = attach_embeddings(plan.documents, embedder=embedder, embedding_model=embedding_model)
    engine = create_engine(get_ai_database_settings().sqlalchemy_url, future=True)
    now = datetime.now(UTC)
    inserted_documents = 0
    inserted_chunks = 0
    failed_count = len(plan.errors)
    status = "SUCCESS"
    error_message = None

    try:
        with Session(engine) as session:
            if force:
                session.execute(
                    delete(AiMajorDocument).where(AiMajorDocument.dataset_version == dataset_version)
                )
                session.flush()

            for document in documents:
                db_document, inserted = _upsert_document(session, document)
                inserted_documents += int(inserted)
                for chunk in document.chunks:
                    inserted_chunks += int(_insert_chunk_if_absent(session, db_document.id, chunk, embedding_model))

            session.add(
                AiEmbeddingJob(
                    job_id=job_id,
                    dataset_version=dataset_version,
                    embedding_model=embedding_model,
                    status=status,
                    started_at=now,
                    finished_at=datetime.now(UTC),
                    total_documents=len(plan.documents),
                    total_chunks=plan.chunk_count,
                    failed_count=failed_count,
                    error_message=error_message,
                )
            )
            session.commit()
    except Exception:
        status = "FAILED"
        raise
    finally:
        engine.dispose()

    return IngestionResult(
        job_id=job_id,
        status=status,
        documents_seen=len(plan.documents),
        chunks_seen=plan.chunk_count,
        documents_inserted=inserted_documents,
        chunks_inserted=inserted_chunks,
        failed_count=failed_count,
        errors=plan.errors,
        dry_run=False,
    )


def attach_embeddings(
    documents: list[ParsedDocument],
    *,
    embedder: EmbeddingProvider,
    embedding_model: str,
) -> list[ParsedDocument]:
    updated_documents: list[ParsedDocument] = []
    for document in documents:
        texts = [chunk.chunk_text for chunk in document.chunks]
        vectors = embedder.embed_texts(texts)
        if len(vectors) != len(texts):
            raise ValueError("embedding provider returned a different number of vectors")

        chunks: list[ParsedChunk] = []
        for chunk, vector in zip(document.chunks, vectors, strict=True):
            if vector is not None and len(vector) != get_embedding_dimension():
                raise ValueError(
                    f"embedding dimension mismatch: model={embedding_model} "
                    f"expected={get_embedding_dimension()} actual={len(vector)}"
                )
            chunks.append(
                ParsedChunk(
                    major_key=chunk.major_key,
                    major_name=chunk.major_name,
                    chunk_index=chunk.chunk_index,
                    chunk_text=chunk.chunk_text,
                    chunk_type=chunk.chunk_type,
                    source_type=chunk.source_type,
                    content_hash=chunk.content_hash,
                    metadata_json=chunk.metadata_json,
                    token_count=chunk.token_count,
                    embedding=vector,
                )
            )
        updated_documents.append(
            ParsedDocument(
                major_key=document.major_key,
                major_name=document.major_name,
                standard_major_name=document.standard_major_name,
                source_type=document.source_type,
                source_path=document.source_path,
                dataset_version=document.dataset_version,
                content_hash=document.content_hash,
                metadata_json=document.metadata_json,
                chunks=chunks,
            )
        )
    return updated_documents


class EmbeddingProvider:
    def embed_texts(self, texts: list[str]) -> list[list[float] | None]:
        raise NotImplementedError


class NoopEmbeddingProvider(EmbeddingProvider):
    def __init__(self, *, dimension: int) -> None:
        self.dimension = dimension

    def embed_texts(self, texts: list[str]) -> list[list[float] | None]:
        return [None for _ in texts]


def create_embedding_provider(*, model: str, dimension: int) -> EmbeddingProvider:
    provider = os.getenv("EMBEDDING_PROVIDER", "openai-compatible").strip().lower()
    if provider in {"openai", "openai-compatible", "factchat"}:
        return OpenAICompatibleEmbeddingProvider(model=model, dimension=dimension)
    if provider == "gemini":
        return GeminiEmbeddingProvider(model=model, dimension=dimension)
    raise RuntimeError(f"Unsupported EMBEDDING_PROVIDER: {provider}")


class OpenAICompatibleEmbeddingProvider(EmbeddingProvider):
    def __init__(self, *, model: str, dimension: int) -> None:
        self.model = model
        self.dimension = dimension
        self.api_key = os.getenv("EMBEDDING_API_KEY") or os.getenv("LLM_API_KEY") or os.getenv("OPENAI_API_KEY")
        self.base_url = os.getenv("EMBEDDING_BASE_URL") or os.getenv("LLM_BASE_URL") or None
        self.batch_size = get_embedding_batch_size()
        self.output_dimensions = get_embedding_output_dimensions()
        if not self.api_key:
            raise RuntimeError("EMBEDDING_API_KEY, LLM_API_KEY, or OPENAI_API_KEY is required unless --skip-embeddings is used")

    def embed_texts(self, texts: list[str]) -> list[list[float] | None]:
        from openai import OpenAI

        client = OpenAI(api_key=self.api_key, base_url=self.base_url)
        vectors: list[list[float]] = []
        for batch in _batched(texts, self.batch_size):
            request = {"model": self.model, "input": batch}
            if self.output_dimensions is not None:
                request["dimensions"] = self.output_dimensions
            response = client.embeddings.create(**request)
            vectors.extend(list(item.embedding) for item in response.data)
        for vector in vectors:
            if len(vector) != self.dimension:
                raise ValueError(f"embedding dimension mismatch: expected={self.dimension} actual={len(vector)}")
        return vectors


class GeminiEmbeddingProvider(EmbeddingProvider):
    def __init__(self, *, model: str, dimension: int) -> None:
        self.model = model
        self.dimension = dimension
        self.api_key = os.getenv("GEMINI_API_KEY")
        self.batch_size = get_embedding_batch_size()
        self.texts_per_minute = get_embedding_texts_per_minute()
        if not self.api_key:
            raise RuntimeError("GEMINI_API_KEY is required when EMBEDDING_PROVIDER=gemini")

    def embed_texts(self, texts: list[str]) -> list[list[float] | None]:
        from google import genai
        from google.genai import types

        client = genai.Client(api_key=self.api_key)
        vectors: list[list[float]] = []
        config = types.EmbedContentConfig(output_dimensionality=self.dimension)
        for batch in _batched(texts, self.batch_size):
            started_at = time.monotonic()
            response = client.models.embed_content(
                model=self.model,
                contents=batch,
                config=config,
            )
            response_vectors = _extract_gemini_vectors(response)
            vectors.extend(response_vectors)
            _throttle_embedding_batch(started_at, len(batch), self.texts_per_minute)
        for vector in vectors:
            if len(vector) != self.dimension:
                raise ValueError(f"embedding dimension mismatch: expected={self.dimension} actual={len(vector)}")
        return vectors


def get_embedding_dimension() -> int:
    return int(os.getenv("EMBEDDING_DIMENSION", str(EMBEDDING_DIMENSION)))


def get_embedding_output_dimensions() -> int | None:
    value = os.getenv("EMBEDDING_OUTPUT_DIMENSIONS")
    if value is None or value.strip() == "":
        return None
    return int(value)


def get_embedding_batch_size() -> int:
    return max(1, _get_env_int("EMBEDDING_BATCH_SIZE", 32))


def get_embedding_texts_per_minute() -> int:
    return max(1, _get_env_int("EMBEDDING_TEXTS_PER_MINUTE", 90))


def _get_env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None or value.strip() == "":
        return default
    return int(value)


def _throttle_embedding_batch(started_at: float, text_count: int, texts_per_minute: int) -> None:
    min_elapsed_seconds = 60.0 * text_count / texts_per_minute
    elapsed_seconds = time.monotonic() - started_at
    sleep_seconds = min_elapsed_seconds - elapsed_seconds
    if sleep_seconds > 0:
        time.sleep(sleep_seconds)


def _batched(items: list[str], batch_size: int) -> list[list[str]]:
    return [items[index : index + batch_size] for index in range(0, len(items), batch_size)]


def _extract_gemini_vectors(response: Any) -> list[list[float]]:
    embeddings = getattr(response, "embeddings", None)
    if embeddings is None:
        embedding = getattr(response, "embedding", None)
        embeddings = [embedding] if embedding is not None else []

    vectors: list[list[float]] = []
    for embedding in embeddings:
        values = getattr(embedding, "values", None)
        if values is None and isinstance(embedding, dict):
            values = embedding.get("values")
        if values is None:
            raise ValueError("Gemini embedding response does not contain values")
        vectors.append(list(values))
    return vectors


def _upsert_document(session: Session, document: ParsedDocument) -> tuple[AiMajorDocument, bool]:
    existing = session.scalar(
        select(AiMajorDocument).where(
            AiMajorDocument.dataset_version == document.dataset_version,
            AiMajorDocument.content_hash == document.content_hash,
        )
    )
    if existing:
        return existing, False

    db_document = AiMajorDocument(
        major_key=document.major_key,
        major_name=document.major_name,
        standard_major_name=document.standard_major_name,
        source_type=document.source_type,
        source_path=document.source_path,
        dataset_version=document.dataset_version,
        content_hash=document.content_hash,
        metadata_json=document.metadata_json,
    )
    session.add(db_document)
    session.flush()
    return db_document, True


def _insert_chunk_if_absent(
    session: Session,
    document_id: int,
    chunk: ParsedChunk,
    embedding_model: str,
) -> bool:
    stmt = (
        insert(AiMajorChunk)
        .values(
            document_id=document_id,
            major_key=chunk.major_key,
            major_name=chunk.major_name,
            chunk_index=chunk.chunk_index,
            chunk_text=chunk.chunk_text,
            chunk_type=chunk.chunk_type,
            source_type=chunk.source_type,
            token_count=chunk.token_count,
            content_hash=chunk.content_hash,
            embedding_model=embedding_model,
            embedding=chunk.embedding,
            metadata_json=chunk.metadata_json,
        )
        .on_conflict_do_nothing(
            constraint="uq_ai_major_chunks_document_index_hash",
        )
        .returning(AiMajorChunk.id)
    )
    result = session.execute(stmt)
    return result.scalar_one_or_none() is not None


def _build_service_chunks(
    *,
    data: dict[str, Any],
    major_key: str,
    major_name: str,
    source_path: str,
) -> list[ParsedChunk]:
    chunks: list[ParsedChunk] = []
    base_metadata = _service_metadata(data, source_path)
    parts = [
        ("service_summary", _stringify_source_summary(data.get("sourceSummary"))),
        ("description", _optional_text(data.get("description"))),
        ("differentiation", _stringify_json_section(data.get("differentiation"))),
        ("core_axes", _stringify_json_section(data.get("coreAxes"))),
        ("related_jobs", _stringify_list(data.get("relatedJobs") or data.get("relatedOccupations") or data.get("careerPaths"))),
        ("keywords", _stringify_list(data.get("keywords"))),
    ]
    for chunk_type, text in parts:
        for part in split_text(text or ""):
            chunk_hash = stable_hash({"source_path": source_path, "chunk_type": chunk_type, "text": part})
            chunks.append(
                ParsedChunk(
                    major_key=major_key,
                    major_name=major_name,
                    chunk_index=len(chunks),
                    chunk_text=part,
                    chunk_type=chunk_type,
                    source_type="service_json",
                    content_hash=chunk_hash,
                    metadata_json=base_metadata,
                    token_count=estimate_token_count(part),
                )
            )
    return chunks


def _service_metadata(data: dict[str, Any], source_path: str) -> dict[str, Any]:
    return {
        "sourcePath": source_path,
        "sourceType": "service_json",
        "category": data.get("category"),
        "excelRowNumber": data.get("excelRowNumber"),
        "keywords": data.get("keywords") or [],
        "coreAxes": data.get("coreAxes") or {},
        "relatedJobs": data.get("relatedJobs") or data.get("relatedOccupations") or data.get("careerPaths") or [],
        "selectedWeightSource": data.get("selectedWeightSource"),
    }


def _rag_metadata(data: dict[str, Any], source_path: str, line_no: int) -> dict[str, Any]:
    metadata = dict(data.get("metadata") or {})
    metadata.update(
        {
            "sourcePath": source_path,
            "sourceType": "rag_jsonl",
            "lineNumber": line_no,
            "chunkId": data.get("chunkId"),
            "category": data.get("category"),
            "keywords": data.get("keywords") or [],
        }
    )
    if data.get("scores") is not None:
        metadata["scores"] = data.get("scores")
    return metadata


def _stringify_source_summary(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        return " ".join(f"{key}: {_stringify_json_section(item)}" for key, item in value.items() if item)
    return str(value)


def _stringify_json_section(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def _stringify_list(value: Any) -> str | None:
    if not value:
        return None
    if isinstance(value, list):
        return ", ".join(str(item) for item in value)
    return str(value)


def _first_text(data: dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = data.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _as_text(value: Any) -> str:
    return str(value).strip() if value is not None else ""


def _optional_text(value: Any) -> str | None:
    text = _as_text(value)
    return text or None


def _is_meaningful_chunk(text: str) -> bool:
    return len((text or "").strip()) >= MIN_CHUNK_CHARS


def _major_name_from_filename(path: Path) -> str:
    name = path.name
    name = re.sub(r"^major-row-\d+-", "", name)
    name = re.sub(r"\.(service\.json|rag\.jsonl)$", "", name)
    return name


def _relative_path(path: Path, root: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


def _apply_limit(paths: list[Path], limit: int | None) -> list[Path]:
    if limit is None:
        return paths
    return paths[: max(limit, 0)]
