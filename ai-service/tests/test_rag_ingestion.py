from __future__ import annotations

import json

import pytest

from app.rag.ingestion import (
    EmbeddingProvider,
    GeminiEmbeddingProvider,
    OpenAICompatibleEmbeddingProvider,
    _extract_gemini_vectors,
    attach_embeddings,
    create_embedding_provider,
    get_embedding_batch_size,
    get_embedding_texts_per_minute,
    ingest_dataset,
    normalize_major_key,
    parse_dataset,
    parse_rag_jsonl,
    parse_service_json,
    stable_hash,
)


class WrongDimensionEmbeddingProvider(EmbeddingProvider):
    def embed_texts(self, texts: list[str]) -> list[list[float] | None]:
        return [[0.1, 0.2] for _ in texts]


class FakeGeminiEmbedding:
    def __init__(self, values: list[float]) -> None:
        self.values = values


class FakeGeminiResponse:
    def __init__(self, vectors: list[list[float]]) -> None:
        self.embeddings = [FakeGeminiEmbedding(vector) for vector in vectors]


def write_sample_dataset(tmp_path):
    majors = tmp_path / "majors"
    rag = tmp_path / "rag" / "per-major"
    majors.mkdir(parents=True)
    rag.mkdir(parents=True)

    service_path = majors / "major-row-1-소프트웨어학과.service.json"
    service_path.write_text(
        json.dumps(
            {
                "majorName": "소프트웨어학과",
                "standardMajorName": "소프트웨어학과",
                "category": "공학계열",
                "sourceSummary": "소프트웨어학과는 구현력과 문제해결을 중심으로 실제 서비스를 만드는 전공이다.",
                "description": "웹, 앱, 서버, 인공지능 서비스를 설계하고 구현한다.",
                "relatedJobs": ["백엔드 개발자", "AI 엔지니어"],
                "keywords": ["프로그래밍", "서비스 구현"],
                "coreAxes": {"primary": ["softwareImplementationScore"]},
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    rag_path = rag / "major-row-1-소프트웨어학과.rag.jsonl"
    rag_lines = [
        {
            "chunkId": "major-1-overview",
            "majorName": "소프트웨어학과",
            "standardMajorName": "소프트웨어학과",
            "category": "공학계열",
            "chunkType": "major_overview",
            "content": "소프트웨어학과는 요구사항 분석, 구현, 테스트, 배포 과정을 반복하며 완성도를 높이는 전공이다.",
            "keywords": ["소프트웨어학과", "구현"],
            "metadata": {"datasetVersion": "test-v1"},
        },
        "{invalid json",
    ]
    rag_path.write_text(
        "\n".join(line if isinstance(line, str) else json.dumps(line, ensure_ascii=False) for line in rag_lines),
        encoding="utf-8",
    )
    return tmp_path


def test_normalize_major_key() -> None:
    assert normalize_major_key(" 소프트웨어 학과 ") == "소프트웨어학과"
    assert normalize_major_key("Computer-Science (AI)") == "computerscienceai"


def test_stable_hash_is_deterministic() -> None:
    left = stable_hash({"b": 2, "a": 1})
    right = stable_hash({"a": 1, "b": 2})
    assert left == right
    assert len(left) == 64


def test_parse_service_json(tmp_path) -> None:
    dataset_root = write_sample_dataset(tmp_path)
    service_path = next((dataset_root / "majors").glob("*.service.json"))

    document = parse_service_json(service_path, dataset_root=dataset_root, dataset_version="test-v1")

    assert document.major_name == "소프트웨어학과"
    assert document.major_key == "소프트웨어학과"
    assert document.source_type == "service_json"
    assert document.dataset_version == "test-v1"
    assert document.chunks
    assert {chunk.chunk_type for chunk in document.chunks} >= {"service_summary", "description"}


def test_parse_rag_jsonl_collects_line_errors(tmp_path) -> None:
    dataset_root = write_sample_dataset(tmp_path)
    rag_path = next((dataset_root / "rag" / "per-major").glob("*.rag.jsonl"))

    document, errors = parse_rag_jsonl(rag_path, dataset_root=dataset_root, dataset_version="test-v1")

    assert document.major_name == "소프트웨어학과"
    assert document.source_type == "rag_jsonl"
    assert len(document.chunks) == 1
    assert document.chunks[0].chunk_type == "major_overview"
    assert len(errors) == 1
    assert "invalid jsonl line" in errors[0].message


def test_parse_dataset_and_dry_run(tmp_path) -> None:
    dataset_root = write_sample_dataset(tmp_path)

    plan = parse_dataset(dataset_root=dataset_root, dataset_version="test-v1")
    result = ingest_dataset(
        dataset_root=dataset_root,
        dataset_version="test-v1",
        embedding_model="text-embedding-3-small",
        dry_run=True,
        skip_embeddings=True,
    )

    assert len(plan.documents) == 2
    assert plan.chunk_count >= 3
    assert result.status == "DRY_RUN"
    assert result.documents_seen == 2
    assert result.chunks_seen == plan.chunk_count
    assert result.failed_count == 1


def test_embedding_dimension_mismatch_is_rejected(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("EMBEDDING_DIMENSION", "1536")
    dataset_root = write_sample_dataset(tmp_path)
    plan = parse_dataset(dataset_root=dataset_root, dataset_version="test-v1", limit=1)

    with pytest.raises(ValueError, match="embedding dimension mismatch"):
        attach_embeddings(
            plan.documents,
            embedder=WrongDimensionEmbeddingProvider(),
            embedding_model="bad-model",
        )


def test_embedding_provider_selection_openai_compatible(monkeypatch) -> None:
    monkeypatch.setenv("EMBEDDING_PROVIDER", "openai-compatible")
    monkeypatch.setenv("EMBEDDING_API_KEY", "test-key")
    provider = create_embedding_provider(model="text-embedding-3-small", dimension=1536)
    assert isinstance(provider, OpenAICompatibleEmbeddingProvider)


def test_embedding_provider_selection_gemini(monkeypatch) -> None:
    monkeypatch.setenv("EMBEDDING_PROVIDER", "gemini")
    monkeypatch.setenv("GEMINI_API_KEY", "test-key")
    provider = create_embedding_provider(model="gemini-embedding-001", dimension=1536)
    assert isinstance(provider, GeminiEmbeddingProvider)


def test_gemini_provider_requires_key(monkeypatch) -> None:
    monkeypatch.setenv("EMBEDDING_PROVIDER", "gemini")
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    with pytest.raises(RuntimeError, match="GEMINI_API_KEY"):
        create_embedding_provider(model="gemini-embedding-001", dimension=1536)


def test_extract_gemini_vectors() -> None:
    vectors = _extract_gemini_vectors(FakeGeminiResponse([[0.1, 0.2], [0.3, 0.4]]))
    assert vectors == [[0.1, 0.2], [0.3, 0.4]]


def test_embedding_env_int_defaults_when_empty(monkeypatch) -> None:
    monkeypatch.setenv("EMBEDDING_BATCH_SIZE", "")
    monkeypatch.setenv("EMBEDDING_TEXTS_PER_MINUTE", "")

    assert get_embedding_batch_size() == 32
    assert get_embedding_texts_per_minute() == 90
