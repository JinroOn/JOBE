from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

from sqlalchemy import create_engine, func, select
from sqlalchemy.orm import Session

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.db import get_ai_database_settings
from app.rag.ingestion import ingest_dataset
from app.rag.models import AiMajorChunk, AiMajorDocument


def _truthy(value: str | None) -> bool:
    return (value or "").strip().lower() in {"1", "true", "yes", "y", "on"}


def _run(command: list[str]) -> None:
    subprocess.run(command, check=True)


def _embedded_chunk_count(dataset_version: str, embedding_model: str) -> int:
    engine = create_engine(get_ai_database_settings().sqlalchemy_url, future=True)
    try:
        with Session(engine) as session:
            stmt = (
                select(func.count(AiMajorChunk.id))
                .join(AiMajorDocument, AiMajorDocument.id == AiMajorChunk.document_id)
                .where(
                    AiMajorDocument.dataset_version == dataset_version,
                    AiMajorChunk.embedding_model == embedding_model,
                    AiMajorChunk.embedding.is_not(None),
                )
            )
            return int(session.scalar(stmt) or 0)
    finally:
        engine.dispose()


def _auto_ingest_if_needed() -> None:
    if not _truthy(os.getenv("AI_RAG_AUTO_INGEST_ENABLED", "false")):
        print("rag auto-ingest disabled")
        return

    dataset_version = os.getenv("AI_RAG_AUTO_INGEST_DATASET_VERSION", "local-openai-large-v1").strip()
    dataset_root = Path(os.getenv("AI_RAG_AUTO_INGEST_DATASET_ROOT", "/datasets")).resolve()
    embedding_model = os.getenv("EMBEDDING_MODEL", "text-embedding-3-large").strip()

    if not dataset_version:
        raise RuntimeError("AI_RAG_AUTO_INGEST_DATASET_VERSION must not be empty")
    if not dataset_root.exists():
        raise RuntimeError(f"RAG dataset root does not exist: {dataset_root}")

    existing = _embedded_chunk_count(dataset_version, embedding_model)
    if existing > 0:
        print(
            "rag auto-ingest skipped: "
            f"datasetVersion={dataset_version} embeddingModel={embedding_model} embeddedChunks={existing}"
        )
        return

    print(
        "rag auto-ingest started: "
        f"datasetVersion={dataset_version} embeddingModel={embedding_model} datasetRoot={dataset_root}"
    )
    result = ingest_dataset(
        dataset_root=dataset_root,
        dataset_version=dataset_version,
        embedding_model=embedding_model,
        dry_run=False,
        force=True,
        skip_embeddings=False,
    )
    print(
        "rag auto-ingest finished: "
        f"status={result.status} documents={result.documents_inserted}/{result.documents_seen} "
        f"chunks={result.chunks_inserted}/{result.chunks_seen} failed={result.failed_count}"
    )
    if result.status == "FAILED":
        raise RuntimeError("RAG auto-ingest failed")


def main() -> None:
    _run([sys.executable, "-m", "alembic", "upgrade", "head"])
    _auto_ingest_if_needed()
    _run(
        [
            sys.executable,
            "-m",
            "uvicorn",
            "app.main:app",
            "--host",
            "0.0.0.0",
            "--port",
            "8001",
        ]
    )


if __name__ == "__main__":
    main()
