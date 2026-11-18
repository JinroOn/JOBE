"""Stage a DEV factual draft without approval, embeddings, jobs or activation.

Default is read-only planning. --apply inserts an immutable single-document
version, then captures its actual manifest in the same transaction. Reusing a
version with different content fails; rerunning identical staging is read-only.
This local operator tool does not authenticate or appoint a human reviewer.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from sqlalchemy import create_engine, select, text
from sqlalchemy.orm import Session

from app.rag.approval import AiEvidenceReview
from app.rag.exploration_binding import ALLOWED_CHUNKS, EvidencePolicy, ExplorationEvidenceBinder
from app.rag.ingestion import normalize_major_key, stable_hash
from app.rag.models import AiMajorChunk, AiMajorDocument
from scripts.verify_rag_backup_migration import (
    HEAD, VerificationFault, inspect_container, require, source_url,
)

DRAFT_KEYS = {"schemaVersion", "proposedDatasetVersion", "status", "scope", "preparedBy", "preparedAt",
              "reviewerId", "reviewedAt", "bindingManifest", "generationAuthorized", "majorCode", "majorName",
              "purpose", "chunks", "sources", "excludedClaims", "beforeApproval"}
SOURCE_KEYS = {"sourceId", "publisher", "title", "url", "accessedAt", "verification", "supports", "limitation"}


def validate_draft(value: dict, mapping: dict) -> dict:
    require(isinstance(value, dict) and set(value) == DRAFT_KEYS, "INVALID_DRAFT_SCHEMA")
    require(value["schemaVersion"] == "EXPLORATION_RAG_REVIEW_DRAFT_V1" and
            value["status"] == "PENDING_HUMAN_REVIEW" and value["scope"] == "DEV" and
            value["generationAuthorized"] is False and
            all(value[k] is None for k in ("reviewerId", "reviewedAt", "bindingManifest")), "UNREVIEWED_DEV_DRAFT_REQUIRED")
    EvidencePolicy(value["proposedDatasetVersion"])
    names = {m["major_code"]: m["major_name"] for m in mapping["majors"]}
    require(names.get(value["majorCode"]) == value["majorName"], "CANONICAL_MAJOR_REQUIRED")
    require(isinstance(value["sources"], list) and 1 <= len(value["sources"]) <= 8, "INVALID_SOURCES")
    ids = set()
    for source in value["sources"]:
        require(isinstance(source, dict) and set(source) == SOURCE_KEYS and
                all(isinstance(v, str) and v.strip() and len(v) <= 2048 for v in source.values()), "INVALID_SOURCE")
        url = urlsplit(source["url"])
        require(url.scheme == "https" and bool(url.hostname) and url.username is None and url.password is None,
                "HTTPS_SOURCE_REQUIRED")
        require(source["sourceId"] not in ids, "DUPLICATE_SOURCE_ID")
        ids.add(source["sourceId"])
    require(isinstance(value["chunks"], list) and 1 <= len(value["chunks"]) <= 8, "INVALID_CHUNKS")
    for chunk in value["chunks"]:
        require(isinstance(chunk, dict) and set(chunk) == {"chunkType", "text", "sourceIds"} and
                chunk["chunkType"] in ALLOWED_CHUNKS and isinstance(chunk["text"], str) and
                20 <= len(chunk["text"].strip()) <= 1600, "FACT_CHUNK_REQUIRED")
        require(isinstance(chunk["sourceIds"], list) and bool(chunk["sourceIds"]) and
                all(isinstance(v, str) for v in chunk["sourceIds"]) and
                len(chunk["sourceIds"]) == len(set(chunk["sourceIds"])) and set(chunk["sourceIds"]) <= ids,
                "UNKNOWN_OR_DUPLICATE_SOURCE")
    return value


def payloads(draft: dict, source_path: str):
    version = draft["proposedDatasetVersion"]
    name = draft["majorName"]
    digest = stable_hash(draft)
    document = dict(major_key=normalize_major_key(name), major_name=name, standard_major_name=name,
                    source_type="manual", source_path=source_path, dataset_version=version,
                    content_hash=digest, metadata_json={"majorCode": draft["majorCode"],
                    "reviewDraftHash": digest, "sources": draft["sources"], "contentStatus": "PENDING_HUMAN_REVIEW"})
    chunks = [dict(major_key=document["major_key"], major_name=name, chunk_index=index,
                   chunk_text=c["text"], chunk_type=c["chunkType"], source_type="manual",
                   content_hash=stable_hash({"documentHash": digest, "index": index, **c}),
                   embedding_model="not_embedded_review_draft", embedding=None,
                   metadata_json={"sourceIds": c["sourceIds"]}) for index, c in enumerate(draft["chunks"])]
    return document, chunks


def stage(engine, draft: dict, relative_path: str, *, apply: bool) -> dict:
    expected_doc, expected_chunks = payloads(draft, relative_path)
    version = draft["proposedDatasetVersion"]
    with Session(engine) as session, session.begin():
        if not apply:
            session.execute(text("SET TRANSACTION READ ONLY"))
        session.execute(text("SET LOCAL lock_timeout='5s'"))
        session.execute(text("SET LOCAL statement_timeout='30s'"))
        require(session.scalars(text("SELECT version_num FROM alembic_version")).all() == [HEAD], "MIGRATION_HEAD_REQUIRED")
        if apply:
            # Serialize staging against ingestion/review writes, including other tools.
            session.execute(text("LOCK TABLE ai_major_documents, ai_major_chunks, ai_evidence_reviews IN SHARE ROW EXCLUSIVE MODE"))
        documents = session.scalars(select(AiMajorDocument).where(AiMajorDocument.dataset_version == version)).all()
        require(len(documents) <= 1, "VERSION_ALREADY_CONTAINS_OTHER_DOCUMENTS")
        created = False
        if documents:
            doc = documents[0]
            require(all(getattr(doc, k) == v for k, v in expected_doc.items()), "IMMUTABLE_VERSION_CONFLICT")
            chunks = session.scalars(select(AiMajorChunk).where(AiMajorChunk.document_id == doc.id)
                                     .order_by(AiMajorChunk.chunk_index)).all()
            require(len(chunks) == len(expected_chunks) and all(
                all(getattr(row, k) == v for k, v in expected.items())
                for row, expected in zip(chunks, expected_chunks)), "IMMUTABLE_CHUNK_CONFLICT")
            require(session.scalar(select(AiEvidenceReview.id).where(AiEvidenceReview.document_id == doc.id).limit(1)) is None,
                    "DOCUMENT_ALREADY_REVIEWED_USE_REVIEW_WORKFLOW")
        elif apply:
            doc = AiMajorDocument(**expected_doc)
            session.add(doc)
            session.flush()
            for chunk in expected_chunks:
                session.add(AiMajorChunk(document_id=doc.id, **chunk))
            session.flush()
            created = True
        else:
            doc = None
        preview = (ExplorationEvidenceBinder(session, EvidencePolicy(version)).capture_review_manifest(doc.id)
                   if doc is not None else None)
        result = {"schemaVersion": "RAG_STAGED_REVIEW_PACKET_V1", "status": "PENDING_HUMAN_REVIEW",
                  "operation": "CREATED" if created else "UNCHANGED" if doc else "WOULD_CREATE",
                  "datasetVersion": version, "scope": "DEV", "documentId": doc.id if doc else None,
                  "contentHash": expected_doc["content_hash"], "sourcePath": relative_path,
                  "majorCode": draft["majorCode"], "majorName": draft["majorName"],
                  "sources": draft["sources"], "chunks": draft["chunks"], "bindingManifest": preview,
                  "reviewerId": None, "reviewedAt": None, "decision": None,
                  "generationAuthorized": False, "embeddingPerformed": False,
                  "limitation": "A local review packet is not an approval receipt or proof of reviewer authorization."}
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--draft", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--source-container", default="jobe-ai-postgres")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)
    path, output = args.draft.resolve(), args.report.resolve()
    require(path.is_relative_to(REPO / "datasets/exploration-rag-review") and path.is_file(), "REVIEW_DRAFT_PATH_REQUIRED")
    require(not output.is_relative_to(REPO) and not output.exists() and output.parent.is_dir(), "NEW_EXTERNAL_REPORT_REQUIRED")
    require(args.env_file.is_file(), "ENV_FILE_REQUIRED")
    draft = validate_draft(json.loads(path.read_text(encoding="utf-8")),
                           json.loads((REPO / "datasets/exploration/major-mapping.json").read_text(encoding="utf-8")))
    url = source_url(args.env_file)
    ports = inspect_container(args.source_container).get("NetworkSettings", {}).get("Ports", {}).get("5432/tcp") or []
    require(any(int(p["HostPort"]) == url.port and p["HostIp"] in {"127.0.0.1", "::1"} for p in ports),
            "SOURCE_CONTAINER_ENDPOINT_MISMATCH")
    engine = create_engine(url.set(drivername="postgresql+psycopg"))
    try:
        report = stage(engine, draft, path.relative_to(REPO / "datasets").as_posix(), apply=args.apply)
    finally:
        engine.dispose()
    # Commit has completed. A report write failure never triggers an automatic deletion;
    # rerun with a fresh output path to recover the same staged document and manifest.
    with output.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({k: report[k] for k in ("operation", "status", "documentId", "generationAuthorized")}))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except VerificationFault as error:
        print(error.code, file=sys.stderr)
        raise SystemExit(1) from None
    except Exception:
        print("RAG staging failed. Recheck staging state; no approval or activation is performed by this tool.", file=sys.stderr)
        raise SystemExit(1) from None
