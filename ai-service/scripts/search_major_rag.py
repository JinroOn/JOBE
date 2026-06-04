from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dotenv import load_dotenv

load_dotenv(dotenv_path=ROOT / ".env")

from app.rag.retrieval import retrieve_major_context  # noqa: E402
from app.rag.schemas import RetrievalRequest  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Search JOBE major RAG chunks from AI PostgreSQL.")
    parser.add_argument("--major-name", required=True)
    parser.add_argument("--dataset-version", default=None)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--score-threshold", type=float, default=0.3)
    parser.add_argument("--request-id", default=None)
    parser.add_argument("--log-retrieval", action="store_true")
    parser.add_argument("--skip-vector", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    result = retrieve_major_context(
        RetrievalRequest(
            majorName=args.major_name,
            datasetVersion=args.dataset_version,
            topK=args.top_k,
            scoreThreshold=args.score_threshold,
            requestId=args.request_id,
            logRetrieval=args.log_retrieval,
            enableVectorSearch=not args.skip_vector,
        )
    )
    print(result.model_dump_json(indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
