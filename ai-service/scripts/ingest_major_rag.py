from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dotenv import load_dotenv

load_dotenv(dotenv_path=ROOT / ".env")

from app.rag.ingestion import get_embedding_dimension, ingest_dataset  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Ingest JOBE major/RAG datasets into AI PostgreSQL.")
    parser.add_argument("--dataset-root", type=Path, default=ROOT.parent / "datasets")
    parser.add_argument("--dataset-version", required=True)
    parser.add_argument("--embedding-model", default=os.getenv("EMBEDDING_MODEL") or "text-embedding-3-large")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--skip-embeddings", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    embedding_model = args.embedding_model
    result = ingest_dataset(
        dataset_root=args.dataset_root.resolve(),
        dataset_version=args.dataset_version,
        embedding_model=embedding_model,
        dry_run=args.dry_run,
        limit=args.limit,
        force=args.force,
        skip_embeddings=args.skip_embeddings,
    )
    print(
        json.dumps(
            {
                "jobId": result.job_id,
                "status": result.status,
                "dryRun": result.dry_run,
                "datasetVersion": args.dataset_version,
                "embeddingModel": embedding_model,
                "embeddingProvider": os.getenv("EMBEDDING_PROVIDER", "openai-compatible"),
                "embeddingDimension": get_embedding_dimension(),
                "documentsSeen": result.documents_seen,
                "chunksSeen": result.chunks_seen,
                "documentsInserted": result.documents_inserted,
                "chunksInserted": result.chunks_inserted,
                "failedCount": result.failed_count,
                "errors": [error.__dict__ for error in result.errors[:20]],
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 1 if result.status == "FAILED" else 0


if __name__ == "__main__":
    raise SystemExit(main())
