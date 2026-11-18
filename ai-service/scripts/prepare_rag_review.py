"""Export a read-only review inventory. This is neither approval nor activation.

Run from ai-service with an explicit dataset version. Output must be outside the
backend repository; source credentials, vectors and legacy score metadata are
never exported. Lexical warnings are review hints, not semantic decisions.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from sqlalchemy import create_engine, select, text
from sqlalchemy.orm import Session

from app.rag.exploration_binding import ALLOWED_CHUNKS, EvidencePolicy, ExplorationEvidenceBinder
from app.rag.ingestion import normalize_major_key, parse_rag_jsonl, parse_service_json
from app.rag.models import AiMajorChunk, AiMajorDocument
from scripts.verify_rag_backup_migration import manifest, readonly, source_url

REVIEW_WORDS = re.compile(r"적성|역량|사고력|능력|잘 맞|적합|중요하다|유리하다")


def source_path(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    if Path(relative).is_absolute() or not path.is_relative_to(root.resolve()):
        raise ValueError("SOURCE_PATH_OUTSIDE_DATASETS")
    return path


def major_code(path: str) -> str | None:
    match = re.search(r"(?:^|/)major-row-(\d+)-", path)
    return f"MAJOR_ROW_{match[1]}" if match else None


def source_urls(value) -> list[str]:
    """Only explicit provenance URLs, never arbitrary metadata or score values."""
    found = set()
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "sourceUrl" and isinstance(child, str) and child.startswith("https://"):
                found.add(child)
            elif isinstance(child, (dict, list)):
                found.update(source_urls(child))
    elif isinstance(value, list):
        for child in value:
            found.update(source_urls(child))
    return sorted(found)


def audit_document(doc, chunks, canonical: dict, dataset_root: Path) -> dict:
    code = major_code(doc.source_path or "")
    expected = canonical.get(code)
    flags = []
    if expected is None:
        flags.append("UNMAPPED_MAJOR")
    elif (doc.major_name != expected or
          doc.major_key != normalize_major_key(expected) or
          (doc.standard_major_name and doc.standard_major_name != expected)):
        flags.append("CANONICAL_NAME_MISMATCH")
    urls = []
    try:
        path = source_path(dataset_root, doc.source_path or "")
        if doc.source_type == "service_json":
            parsed = parse_service_json(path, dataset_root=dataset_root, dataset_version=doc.dataset_version)
            urls = source_urls(json.loads(path.read_text(encoding="utf-8")))
            errors = []
        elif doc.source_type == "rag_jsonl":
            parsed, errors = parse_rag_jsonl(path, dataset_root=dataset_root, dataset_version=doc.dataset_version)
            urls = sorted({url for c in parsed.chunks for url in source_urls(c.metadata_json)})
        else:
            raise ValueError("UNSUPPORTED_LOCAL_SOURCE")
        if errors:
            flags.append("LOCAL_PARSE_ERRORS")
        # Hash alone is insufficient: also compare the actual stored text/metadata.
        def signature(c):
            return (c.chunk_index, c.chunk_type, c.chunk_text, c.content_hash,
                    c.major_key, c.major_name, c.source_type, c.metadata_json)
        if (doc.content_hash != parsed.content_hash or doc.major_key != parsed.major_key or
                doc.major_name != parsed.major_name or doc.standard_major_name != parsed.standard_major_name or
                doc.metadata_json != parsed.metadata_json or
                [signature(c) for c in sorted(chunks, key=lambda c: c.chunk_index)] !=
                [signature(c) for c in parsed.chunks]):
            flags.append("LOCAL_SOURCE_DRIFT")
    except (OSError, ValueError, KeyError, TypeError):
        flags.append("LOCAL_SOURCE_UNVERIFIABLE")
    facts = []
    for chunk in chunks:
        if chunk.chunk_type not in ALLOWED_CHUNKS:
            continue
        warnings = ["SUITABILITY_OR_ABILITY_WORDING"] if REVIEW_WORDS.search(chunk.chunk_text) else []
        if warnings and "FACT_TEXT_REVIEW_REQUIRED" not in flags:
            flags.append("FACT_TEXT_REVIEW_REQUIRED")
        facts.append({"chunkId": chunk.id, "chunkType": chunk.chunk_type,
                      "declaredHash": chunk.content_hash, "text": chunk.chunk_text,
                      "warnings": warnings})
    if not facts:
        flags.append("NO_ALLOWED_FACT_CHUNKS")
    if not urls:
        flags.append("NO_EXPLICIT_SOURCE_URL")
    return {"documentId": doc.id, "majorCode": code, "canonicalName": expected,
            "storedName": doc.major_name, "sourceType": doc.source_type,
            "sourcePath": doc.source_path, "declaredHash": doc.content_hash,
            "flags": flags, "facts": facts, "excludedChunkCount": len(chunks) - len(facts),
            "localSourceUrls": urls, "sourceContentVerified": False,
            "reviewStatus": "PENDING_HUMAN_REVIEW"}


def build_report(engine, dataset_version: str, mapping: Path, dataset_root: Path) -> dict:
    policy = EvidencePolicy(dataset_version)
    mapping_data = json.loads(mapping.read_text(encoding="utf-8"))
    canonical = {m["major_code"]: m["major_name"] for m in mapping_data["majors"]}
    with Session(engine) as session:
        session.execute(text("SET TRANSACTION READ ONLY"))
        # One statement snapshot for documents/chunks; no ORM relationship writes.
        rows = session.execute(select(AiMajorDocument, AiMajorChunk).outerjoin(
            AiMajorChunk, AiMajorChunk.document_id == AiMajorDocument.id).where(
            AiMajorDocument.dataset_version == dataset_version).order_by(
            AiMajorDocument.id, AiMajorChunk.chunk_index)).all()
        grouped = {}
        for doc, chunk in rows:
            grouped.setdefault(doc.id, (doc, []))
            if chunk is not None:
                grouped[doc.id][1].append(chunk)
        if not grouped:
            raise ValueError("EXPLICIT_DATASET_NOT_FOUND")
        inventory = [audit_document(doc, chunks, canonical, dataset_root) for doc, chunks in grouped.values()]
        # Manifests are attestations of current stored bytes, not source approval.
        binder = ExplorationEvidenceBinder(session, policy)
        previews = {str(item["documentId"]): binder.capture_review_manifest(item["documentId"])
                    for item in inventory if item["majorCode"] in {"MAJOR_ROW_133", "MAJOR_ROW_6", "MAJOR_ROW_93"}}
        versions = dict(session.execute(text(
            "SELECT dataset_version, count(*) FROM ai_major_documents GROUP BY dataset_version ORDER BY dataset_version"
        )).all())
        reviews = session.scalar(text("SELECT count(*) FROM ai_evidence_reviews"))
    covered = {item["majorCode"] for item in inventory if item["majorCode"] in canonical}
    return {"schemaVersion": "RAG_REVIEW_INVENTORY_V1", "preparedAt": datetime.now(timezone.utc).isoformat(),
            "datasetVersion": dataset_version, "scope": "DEV", "status": "PENDING_HUMAN_REVIEW",
            "generationAuthorized": False, "reviewerId": None,
            "mappingVersion": mapping_data["major_mapping_version"],
            "summary": {"documents": len(inventory), "canonicalMajors": len(canonical),
                        "coveredMajors": len(covered), "missingMajors": sorted(set(canonical) - covered),
                        "flagCounts": dict(Counter(flag for item in inventory for flag in item["flags"])),
                        "excludedDocumentsByVersion": {k: v for k, v in versions.items() if k != dataset_version},
                        "existingReviewRows": reviews},
            "documents": inventory, "storedManifestPreviews": previews,
            "limitations": ["Lexical warnings are not semantic review or approval.",
                            "Local source URLs have not been verified by this offline tool.",
                            "Re-capture current manifests when appending a real review.",
                            "No database writes, provider calls or activation performed."]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--dataset-version", required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    output = args.report.resolve()
    if output.is_relative_to(REPO) or output.exists() or not output.parent.is_dir():
        raise ValueError("REPORT_MUST_BE_NEW_AND_OUTSIDE_REPOSITORY")
    url = source_url(args.env_file)
    with readonly(url) as connection:
        before = manifest(connection)
    engine = create_engine(url.set(drivername="postgresql+psycopg"))
    try:
        report = build_report(engine, args.dataset_version, REPO / "datasets/exploration/major-mapping.json", REPO / "datasets")
    finally:
        engine.dispose()
    with readonly(url) as connection:
        after = manifest(connection)
    if before != after:
        raise ValueError("DATABASE_CHANGED_DURING_REVIEW_EXPORT")
    report["databaseUnchanged"] = True
    report["databaseManifest"] = after
    with output.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"status": report["status"], "databaseUnchanged": True, **report["summary"]}))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception:
        # Connection/parser failures can contain credentials or arbitrary source text.
        print("RAG review export failed; no approval or activation was performed.", file=sys.stderr)
        raise SystemExit(1) from None
