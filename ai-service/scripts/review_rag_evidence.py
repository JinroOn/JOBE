"""Local DEV review preview and append-only decisions; no automatic approval.

Preview is one read-only statement snapshot. Recording requires an explicitly
completed human decision receipt. Default record mode validates without writing;
--apply serializes content/review writers, rechecks the manifest and appends.
This operator CLI does not authenticate the named reviewer or grant permissions.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from sqlalchemy import create_engine, func, select, text
from sqlalchemy.orm import Session

from app.rag.approval import AiEvidenceReview
from app.rag.exploration_binding import EvidencePolicy, _manifest
from app.rag.models import AiMajorChunk, AiMajorDocument
from scripts.verify_rag_backup_migration import HEAD, VerificationFault, inspect_container, require, source_url

SCHEMA = "DEV_RAG_REVIEW_DECISION_V1"
KEYS = {"schemaVersion", "documentId", "datasetVersion", "scope", "contentHash", "bindingManifest",
        "expectedLatestReviewId", "decision", "reviewerId", "reviewedAt", "sourceReference"}


def validate_receipt(value):
    require(isinstance(value, dict) and set(value) == KEYS and value["schemaVersion"] == SCHEMA,
            "INVALID_RECEIPT_SCHEMA")
    EvidencePolicy(value["datasetVersion"], scope=value["scope"])
    require(type(value["documentId"]) is int and value["documentId"] > 0 and
            (value["expectedLatestReviewId"] is None or
             type(value["expectedLatestReviewId"]) is int and value["expectedLatestReviewId"] > 0), "INVALID_REVIEW_IDS")
    require(value["decision"] in {"APPROVED", "REJECTED", "REVOKED"}, "EXPLICIT_DECISION_REQUIRED")
    require(isinstance(value["reviewerId"], str) and 1 <= len(value["reviewerId"].strip()) <= 120 and
            value["reviewerId"] == value["reviewerId"].strip(), "ACTUAL_REVIEWER_REQUIRED")
    require(isinstance(value["sourceReference"], str) and 1 <= len(value["sourceReference"].strip()) <= 8000,
            "SOURCE_REVIEW_REFERENCE_REQUIRED")
    require(isinstance(value["bindingManifest"], dict) and isinstance(value["contentHash"], str), "MANIFEST_REQUIRED")
    require(isinstance(value["reviewedAt"], str), "ACTUAL_REVIEW_TIME_REQUIRED")
    try:
        reviewed = datetime.fromisoformat(value["reviewedAt"])
    except ValueError:
        raise VerificationFault("ACTUAL_REVIEW_TIME_REQUIRED") from None
    require(reviewed.tzinfo is not None and reviewed.utcoffset() is not None, "TIMEZONE_REQUIRED")
    return reviewed.astimezone(timezone.utc)


def snapshot(session, document_id, version):
    EvidencePolicy(version)
    require(type(document_id) is int and document_id > 0, "INVALID_REVIEW_IDS")
    require(session.get_bind().dialect.name == "postgresql" and
            session.connection().get_isolation_level() == "READ COMMITTED", "READ_COMMITTED_REQUIRED")
    latest = (select(func.max(AiEvidenceReview.id)).where(
        AiEvidenceReview.document_id == AiMajorDocument.id, AiEvidenceReview.scope == "DEV")
        .correlate(AiMajorDocument).scalar_subquery())
    rows = session.execute(select(AiMajorDocument, AiMajorChunk, AiEvidenceReview, func.statement_timestamp())
        .select_from(AiMajorDocument).outerjoin(AiMajorChunk, AiMajorChunk.document_id == AiMajorDocument.id)
        .outerjoin(AiEvidenceReview, AiEvidenceReview.id == latest)
        .where(AiMajorDocument.id == document_id, AiMajorDocument.dataset_version == version)
        .order_by(AiMajorChunk.id).execution_options(populate_existing=True)).all()
    require(bool(rows), "DOCUMENT_OR_VERSION_NOT_FOUND")
    doc, _, review, now = rows[0]
    require(doc.source_type == "manual" and bool(doc.source_path) and
            doc.source_path.startswith("exploration-rag-review/") and
            doc.metadata_json.get("reviewDraftHash") == doc.content_hash, "STAGED_REVIEW_DOCUMENT_REQUIRED")
    chunks = [row[1] for row in rows if row[1] is not None]
    return doc, chunks, review, now, _manifest(doc, chunks)


def setup(session, *, write):
    if not write:
        session.execute(text("SET TRANSACTION READ ONLY"))
    session.execute(text("SET LOCAL lock_timeout='5s'"))
    session.execute(text("SET LOCAL statement_timeout='30s'"))
    require(session.scalars(text("SELECT version_num FROM alembic_version")).all() == [HEAD], "MIGRATION_HEAD_REQUIRED")
    if write:
        session.execute(text("LOCK TABLE ai_major_documents, ai_major_chunks, ai_evidence_reviews IN SHARE ROW EXCLUSIVE MODE"))


def preview(engine, document_id, version):
    with Session(engine) as session, session.begin():
        setup(session, write=False)
        doc, chunks, latest, _, fingerprint = snapshot(session, document_id, version)
        template = dict(schemaVersion=SCHEMA, documentId=doc.id, datasetVersion=version, scope="DEV",
                        contentHash=doc.content_hash, bindingManifest=fingerprint,
                        expectedLatestReviewId=latest.id if latest else None,
                        decision=None, reviewerId=None, reviewedAt=None, sourceReference=None)
        return {"schemaVersion": "DEV_RAG_REVIEW_PREVIEW_V1", "majorName": doc.major_name,
                "latestDecision": latest.decision if latest else None,
                "chunks": [{"chunkId": c.id, "chunkType": c.chunk_type, "text": c.chunk_text} for c in chunks],
                "sources": [{k: str(s.get(k, "")) for k in ("sourceId", "publisher", "title", "url", "supports", "limitation")}
                            for s in doc.metadata_json.get("sources", [])],
                "receiptTemplate": template, "generationAuthorized": False}


def record(engine, receipt, *, apply=False):
    reviewed = validate_receipt(receipt)
    with Session(engine) as session, session.begin():
        setup(session, write=apply)
        doc, _, latest, now, fingerprint = snapshot(session, receipt["documentId"], receipt["datasetVersion"])
        require(receipt["contentHash"] == doc.content_hash and receipt["bindingManifest"] == fingerprint,
                "REVIEWED_CONTENT_CHANGED")
        require(reviewed <= now, "FUTURE_REVIEW_TIME")
        approved = receipt["decision"] == "APPROVED"
        values = dict(document_id=doc.id, dataset_version=doc.dataset_version, content_hash=doc.content_hash,
                      scope="DEV", decision=receipt["decision"], content_status="REVIEWED" if approved else "DRAFT",
                      implementation_status="APPROVED_FOR_IMPLEMENTATION" if approved else "PENDING",
                      domain_status="APPROVED" if approved else "PENDING", reviewer_id=receipt["reviewerId"],
                      source_reference=receipt["sourceReference"], reviewed_at=reviewed, binding_manifest=fingerprint)
        if latest and all(getattr(latest, key) == value for key, value in values.items()):
            operation, review_id = "UNCHANGED", latest.id
        else:
            require(receipt["expectedLatestReviewId"] == (latest.id if latest else None), "LATEST_REVIEW_CHANGED")
            require(latest is None or reviewed >= latest.reviewed_at, "REVIEW_TIME_PRECEDES_LATEST")
            review_id = None
            operation = "WOULD_APPEND"
            if apply:
                row = AiEvidenceReview(**values)
                session.add(row)
                session.flush()
                operation, review_id = "APPENDED", row.id
        return {"operation": operation, "documentId": doc.id, "datasetVersion": doc.dataset_version,
                "scope": "DEV", "decision": receipt["decision"], "reviewId": review_id,
                "generationAuthorized": False,
                "limitation": "A DEV review does not activate a feature, PUBLIC scope or provider dispatch."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["preview", "record"])
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--source-container", default="jobe-ai-postgres")
    parser.add_argument("--document-id", type=int)
    parser.add_argument("--dataset-version")
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)
    output = args.report.resolve()
    require(not output.is_relative_to(REPO) and not output.exists() and output.parent.is_dir(), "NEW_EXTERNAL_REPORT_REQUIRED")
    require(args.env_file.is_file(), "ENV_FILE_REQUIRED")
    require(args.action == "record" or not args.apply, "PREVIEW_IS_READ_ONLY")
    if args.action == "record":
        require(args.receipt is not None and args.receipt.is_file(), "RECEIPT_FILE_REQUIRED")
        receipt = json.loads(args.receipt.read_text(encoding="utf-8"))
        validate_receipt(receipt)
    else:
        require(args.document_id is not None and args.dataset_version is not None, "EXPLICIT_DOCUMENT_VERSION_REQUIRED")
    url = source_url(args.env_file)
    ports = inspect_container(args.source_container).get("NetworkSettings", {}).get("Ports", {}).get("5432/tcp") or []
    require(any(int(p["HostPort"]) == url.port and p["HostIp"] in {"127.0.0.1", "::1"} for p in ports),
            "SOURCE_CONTAINER_ENDPOINT_MISMATCH")
    engine = create_engine(url.set(drivername="postgresql+psycopg"))
    try:
        result = (preview(engine, args.document_id, args.dataset_version) if args.action == "preview"
                  else record(engine, receipt, apply=args.apply))
    finally:
        engine.dispose()
    # A report failure after commit never rolls back history. Replaying the exact
    # latest receipt recovers its ID; an old approval cannot undo a later revocation.
    with output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"action": args.action, "operation": result.get("operation", "PREVIEW"), "generationAuthorized": False}))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except VerificationFault as error:
        print(error.code, file=sys.stderr)
        raise SystemExit(1) from None
    except Exception:
        print("Review operation failed; inspect current history before retrying. No feature activation performed.", file=sys.stderr)
        raise SystemExit(1) from None
