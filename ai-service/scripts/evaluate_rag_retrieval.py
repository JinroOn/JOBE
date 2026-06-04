from __future__ import annotations

import argparse
import json
import statistics
import sys
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from dotenv import load_dotenv

load_dotenv(dotenv_path=ROOT / ".env")

from app.rag.ingestion import normalize_major_key, stable_hash  # noqa: E402
from app.rag.retrieval import retrieve_major_context  # noqa: E402
from app.rag.schemas import RetrievalRequest, RetrievalResult  # noqa: E402

DEFAULT_MAJORS = [
    "소프트웨어학과",
    "데이터사이언스학과",
    "컴퓨터공학과",
    "정보통신공학과",
    "경영학과",
    "심리학과",
]


@dataclass(frozen=True)
class EvaluationRow:
    major_name: str
    matched_by: str
    grade: str
    related_top3: int
    wrong_major_count: int
    duplicate_count: int
    fallback_used: bool
    no_result: bool
    latency_ms: int | None
    dataset_version: str | None
    scores: list[float | None]
    snippet_major_names: list[str]
    matched_chunk_ids: list[int]
    notes: list[str]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate JOBE pgvector RAG retrieval quality without LLM calls.")
    parser.add_argument(
        "--majors",
        default=",".join(DEFAULT_MAJORS),
        help="Comma-separated major names to evaluate.",
    )
    parser.add_argument("--dataset-version", default=None)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--score-threshold", type=float, default=0.3)
    parser.add_argument(
        "--enable-vector",
        action="store_true",
        help="Enable query embedding + pgvector search. Omit this before full ingestion/quota reset.",
    )
    parser.add_argument("--format", choices=["json", "markdown"], default="json")
    parser.add_argument("--output", type=Path, default=None)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    majors = [major.strip() for major in args.majors.split(",") if major.strip()]
    rows = [
        evaluate_major(
            major_name=major,
            dataset_version=args.dataset_version,
            top_k=args.top_k,
            score_threshold=args.score_threshold,
            enable_vector=args.enable_vector,
        )
        for major in majors
    ]
    payload = build_report_payload(rows, args=args)
    output = render_markdown(payload) if args.format == "markdown" else json.dumps(payload, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output + "\n", encoding="utf-8")
    else:
        print(output)
    return 0


def evaluate_major(
    *,
    major_name: str,
    dataset_version: str | None,
    top_k: int,
    score_threshold: float,
    enable_vector: bool,
) -> EvaluationRow:
    result = retrieve_major_context(
        RetrievalRequest(
            majorName=major_name,
            datasetVersion=dataset_version,
            topK=top_k,
            scoreThreshold=score_threshold,
            enableVectorSearch=enable_vector,
            logRetrieval=False,
        )
    )
    return evaluate_result(major_name, result)


def evaluate_result(major_name: str, result: RetrievalResult) -> EvaluationRow:
    expected_key = normalize_major_key(major_name)
    snippet_major_names = [snippet.majorName for snippet in result.ragSnippets]
    related_flags = [normalize_major_key(name) == expected_key for name in snippet_major_names]
    related_top3 = sum(1 for flag in related_flags[:3] if flag)
    wrong_major_count = sum(1 for flag in related_flags if not flag)
    duplicate_count = count_duplicate_snippets([snippet.chunkText for snippet in result.ragSnippets])
    no_result = not result.ragSnippets
    notes: list[str] = []
    if no_result:
        notes.append("no_result")
    if result.retrievalMeta.fallbackUsed:
        notes.append("fallback_used")
    if wrong_major_count:
        notes.append("wrong_major_snippet")
    if duplicate_count:
        notes.append("duplicate_snippet")

    grade = grade_result(
        related_top3=related_top3,
        wrong_major_count=wrong_major_count,
        duplicate_count=duplicate_count,
        fallback_used=result.retrievalMeta.fallbackUsed,
        no_result=no_result,
    )
    return EvaluationRow(
        major_name=major_name,
        matched_by=result.retrievalMeta.matchedBy,
        grade=grade,
        related_top3=related_top3,
        wrong_major_count=wrong_major_count,
        duplicate_count=duplicate_count,
        fallback_used=result.retrievalMeta.fallbackUsed,
        no_result=no_result,
        latency_ms=result.retrievalMeta.latencyMs,
        dataset_version=result.retrievalMeta.datasetVersion,
        scores=[snippet.score for snippet in result.ragSnippets],
        snippet_major_names=snippet_major_names,
        matched_chunk_ids=result.retrievalMeta.matchedChunkIds,
        notes=notes,
    )


def grade_result(
    *,
    related_top3: int,
    wrong_major_count: int,
    duplicate_count: int,
    fallback_used: bool,
    no_result: bool,
) -> str:
    if no_result or fallback_used:
        return "C"
    if wrong_major_count:
        return "D"
    if related_top3 >= 3 and duplicate_count == 0:
        return "A"
    if related_top3 >= 1:
        return "B"
    return "C"


def count_duplicate_snippets(snippets: list[str]) -> int:
    seen: set[str] = set()
    duplicates = 0
    for snippet in snippets:
        digest = stable_hash(" ".join((snippet or "").split())[:240].lower())
        if digest in seen:
            duplicates += 1
        else:
            seen.add(digest)
    return duplicates


def build_report_payload(rows: list[EvaluationRow], *, args: argparse.Namespace) -> dict[str, Any]:
    latencies = [row.latency_ms for row in rows if row.latency_ms is not None]
    fallback_count = sum(1 for row in rows if row.fallback_used)
    no_result_count = sum(1 for row in rows if row.no_result)
    grade_counts = {grade: sum(1 for row in rows if row.grade == grade) for grade in ("A", "B", "C", "D")}
    return {
        "evaluatedAt": datetime.now(UTC).isoformat(),
        "datasetVersionRequested": args.dataset_version,
        "topK": args.top_k,
        "scoreThreshold": args.score_threshold,
        "vectorSearchEnabled": args.enable_vector,
        "summary": {
            "majorCount": len(rows),
            "gradeCounts": grade_counts,
            "fallbackRate": fallback_count / len(rows) if rows else 0,
            "noResultRate": no_result_count / len(rows) if rows else 0,
            "avgLatencyMs": round(statistics.mean(latencies), 2) if latencies else None,
        },
        "rows": [row.__dict__ for row in rows],
        "gradingPolicy": {
            "A": "top3 모두 관련 전공이고 중복이 없음",
            "B": "top3 중 1개 이상 관련 전공이고 생성에 사용 가능",
            "C": "결과 없음 또는 fallback 필요",
            "D": "잘못된 전공 snippet이 섞임",
        },
    }


def render_markdown(payload: dict[str, Any]) -> str:
    lines = [
        "# RAG Retrieval Evaluation Result",
        "",
        f"- Evaluated at: `{payload['evaluatedAt']}`",
        f"- Dataset version requested: `{payload['datasetVersionRequested']}`",
        f"- topK: `{payload['topK']}`",
        f"- scoreThreshold: `{payload['scoreThreshold']}`",
        f"- vectorSearchEnabled: `{payload['vectorSearchEnabled']}`",
        "",
        "## Summary",
        "",
        f"- majorCount: `{payload['summary']['majorCount']}`",
        f"- gradeCounts: `{payload['summary']['gradeCounts']}`",
        f"- fallbackRate: `{payload['summary']['fallbackRate']}`",
        f"- noResultRate: `{payload['summary']['noResultRate']}`",
        f"- avgLatencyMs: `{payload['summary']['avgLatencyMs']}`",
        "",
        "## Rows",
        "",
        "| Major | Grade | MatchedBy | RelatedTop3 | Wrong | Duplicates | Fallback | LatencyMs | Snippet Majors | Notes |",
        "| --- | --- | --- | ---: | ---: | ---: | --- | ---: | --- | --- |",
    ]
    for row in payload["rows"]:
        lines.append(
            "| {major_name} | {grade} | {matched_by} | {related_top3} | {wrong_major_count} | "
            "{duplicate_count} | {fallback_used} | {latency_ms} | {snippet_major_names} | {notes} |".format(
                **row,
            )
        )
    return "\n".join(lines)


if __name__ == "__main__":
    raise SystemExit(main())
