"""Synthetic reviews and writes below use a disposable database, never development data."""
from copy import deepcopy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import uuid

import pytest
from sqlalchemy import create_engine, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import Session

from app.exploration_contract import input_hash
from app.rag.exploration_binding import EvidencePolicy, ExplorationEvidenceBinder
from app.rag.models import AiMajorDocument
from scripts import review_rag_evidence as review, stage_rag_review as workflow
from scripts.verify_rag_backup_migration import HEAD, VerificationFault

REPO = Path(__file__).resolve().parents[2]
DRAFT = json.loads((REPO / "datasets/exploration-rag-review/computer-science.pilot-review.json").read_text(encoding="utf-8"))
MAPPING = json.loads((REPO / "datasets/exploration/major-mapping.json").read_text(encoding="utf-8"))
RELATIVE = "exploration-rag-review/computer-science.pilot-review.json"


def test_current_draft_validates_without_claiming_approval():
    assert workflow.validate_draft(deepcopy(DRAFT), MAPPING) == DRAFT
    assert DRAFT["sources"][0]["sourceId"] == "CAREERNET_CSE_569"


@pytest.mark.parametrize("change,code", [
    ("approved", "UNREVIEWED_DEV_DRAFT_REQUIRED"), ("public", "UNREVIEWED_DEV_DRAFT_REQUIRED"),
    ("reviewer", "UNREVIEWED_DEV_DRAFT_REQUIRED"), ("name", "CANONICAL_MAJOR_REQUIRED"),
    ("score", "FACT_CHUNK_REQUIRED"), ("unknown_source", "UNKNOWN_OR_DUPLICATE_SOURCE"),
    ("http", "HTTPS_SOURCE_REQUIRED"), ("credentials", "HTTPS_SOURCE_REQUIRED"),
    ("duplicate_source", "DUPLICATE_SOURCE_ID"), ("hidden_metadata", "INVALID_DRAFT_SCHEMA"),
])
def test_invalid_draft_cannot_enter_staging(change, code):
    draft = deepcopy(DRAFT)
    if change == "approved": draft["status"] = "APPROVED"
    if change == "public": draft["scope"] = "PUBLIC"
    if change == "reviewer": draft["reviewerId"] = "MADE_UP"
    if change == "name": draft["majorName"] = "Unmapped name"
    if change == "score": draft["chunks"][0]["chunkType"] = "competency_profile"
    if change == "unknown_source": draft["chunks"][0]["sourceIds"] = ["MISSING"]
    if change == "http": draft["sources"][0]["url"] = "http://example.edu"
    if change == "credentials": draft["sources"][0]["url"] = "https://user:password@example.edu"
    if change == "duplicate_source": draft["sources"].append(deepcopy(draft["sources"][0]))
    if change == "hidden_metadata": draft["scores"] = {"ability": 100}
    with pytest.raises(VerificationFault, match=code):
        workflow.validate_draft(draft, MAPPING)


@pytest.fixture
def database():
    import psycopg
    from psycopg import sql
    from alembic import command
    from alembic.config import Config
    value = os.getenv("SECTION32_TEST_DATABASE_URL")
    if not value:
        pytest.skip("Dedicated test PostgreSQL required")
    url = make_url(value)
    assert url.host in {"localhost", "127.0.0.1"} and url.database == "section32_ai_test"
    name = "rag_review_test_" + uuid.uuid4().hex
    admin = psycopg.connect(url.set(drivername="postgresql").render_as_string(hide_password=False), autocommit=True)
    admin.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(name)))
    engine = create_engine(url.set(database=name))
    try:
        with engine.begin() as connection:
            config = Config(str(workflow.ROOT / "alembic.ini"))
            config.set_main_option("script_location", str(workflow.ROOT / "migrations"))
            config.attributes["connection"] = connection
            command.upgrade(config, HEAD)
        yield engine
    finally:
        engine.dispose()
        admin.execute(sql.SQL("DROP DATABASE {}").format(sql.Identifier(name)))
        admin.close()


def counts(engine):
    with engine.connect() as c:
        return [c.scalar(text(f"SELECT count(*) FROM {table}")) for table in
                ("ai_major_documents", "ai_major_chunks", "ai_evidence_reviews", "ai_exploration_jobs")]


def test_default_plan_does_not_insert_or_invent_manifest(database):
    result = workflow.stage(database, DRAFT, RELATIVE, apply=False)
    assert result["operation"] == "WOULD_CREATE"
    assert result["documentId"] is None and result["bindingManifest"] is None
    assert counts(database) == [0, 0, 0, 0]


def test_staging_replay_and_readonly_preview_use_same_actual_document_and_manifest(database):
    first = workflow.stage(database, DRAFT, RELATIVE, apply=True)
    replay = workflow.stage(database, DRAFT, RELATIVE, apply=True)
    preview = workflow.stage(database, DRAFT, RELATIVE, apply=False)
    assert first["operation"] == "CREATED" and replay["operation"] == preview["operation"] == "UNCHANGED"
    assert first["documentId"] == replay["documentId"] == preview["documentId"]
    assert first["bindingManifest"] == replay["bindingManifest"] == preview["bindingManifest"]
    assert len(first["bindingManifest"]["chunks"]) == len(DRAFT["chunks"])
    assert counts(database) == [1, len(DRAFT["chunks"]), 0, 0]


def test_changed_content_cannot_overwrite_or_add_to_fixed_version(database):
    workflow.stage(database, DRAFT, RELATIVE, apply=True)
    changed = deepcopy(DRAFT)
    changed["chunks"][0]["text"] += " Changed factual draft."
    with pytest.raises(VerificationFault, match="IMMUTABLE_VERSION_CONFLICT"):
        workflow.stage(database, changed, RELATIVE, apply=True)
    assert counts(database) == [1, len(DRAFT["chunks"]), 0, 0]


def test_actual_text_change_with_original_declared_hash_blocks_replay(database):
    workflow.stage(database, DRAFT, RELATIVE, apply=True)
    with database.begin() as c:
        c.execute(text("UPDATE ai_major_chunks SET chunk_text='Tampered but declared hash unchanged'"))
    with pytest.raises(VerificationFault, match="IMMUTABLE_CHUNK_CONFLICT"):
        workflow.stage(database, DRAFT, RELATIVE, apply=True)


def test_manifest_failure_rolls_back_new_document_and_all_chunks(database, monkeypatch):
    def fail(*args, **kwargs):
        raise RuntimeError("SYNTHETIC_MANIFEST_FAILURE")
    monkeypatch.setattr(workflow, "_manifest", fail)
    with pytest.raises(RuntimeError, match="SYNTHETIC_MANIFEST_FAILURE"):
        workflow.stage(database, DRAFT, RELATIVE, apply=True)
    assert counts(database) == [0, 0, 0, 0]


def test_staged_facts_are_unavailable_until_real_review_and_do_not_change_legacy(database):
    with Session(database) as s, s.begin():
        s.add(AiMajorDocument(major_key="legacy", major_name="Legacy", source_type="manual",
                             dataset_version="LEGACY_TEST_ONLY", content_hash="a" * 64, metadata_json={"keep": True}))
    result = workflow.stage(database, DRAFT, RELATIVE, apply=True)
    request = json.loads((REPO / "datasets/exploration-ai-contract/cases.json").read_text(encoding="utf-8"))["adapterCases"][0]["expectedRequest"]
    request["input"]["candidateNames"] = [DRAFT["majorName"]]
    request["input"]["targetName"] = None
    request["inputHash"] = input_hash(request)
    with Session(database) as s:
        prepared = ExplorationEvidenceBinder(s, EvidencePolicy(result["datasetVersion"])).prepare(request)
        assert prepared.status == "INSUFFICIENT_EVIDENCE" and prepared.binding is None
        legacy = s.scalar(select(AiMajorDocument).where(AiMajorDocument.dataset_version == "LEGACY_TEST_ONLY"))
        assert legacy.content_hash == "a" * 64 and legacy.metadata_json == {"keep": True}
        assert s.scalar(text("SELECT count(*) FROM ai_major_chunks WHERE embedding IS NOT NULL")) == 0


def batch_drafts():
    paths = [f"exploration-rag-review/major-row-{row}.review-draft.json" for row in (3, 40, 166)]
    return [(workflow.validate_draft(json.loads((REPO / "datasets" / path).read_text(encoding="utf-8")), MAPPING), path)
            for path in paths]


def test_shared_version_requires_explicit_review_for_each_requested_major(database):
    drafts = batch_drafts()
    version = "EXPLORATION_RAG_TEST_COMMON"
    plan = workflow.stage_batch(database, drafts, version, apply=False)
    assert plan["operation"] == "WOULD_CREATE"
    assert all(packet["bindingManifest"] is None for packet in plan["documents"])
    assert counts(database) == [0, 0, 0, 0]

    staged = workflow.stage_batch(database, drafts, version, apply=True)
    replay = workflow.stage_batch(database, list(reversed(drafts)), version, apply=False)
    assert staged["operation"] == "CREATED" and replay["operation"] == "UNCHANGED"
    assert [p["bindingManifest"] for p in staged["documents"]] == [p["bindingManifest"] for p in replay["documents"]]
    assert counts(database) == [3, 6, 0, 0]
    with pytest.raises(VerificationFault, match="IMMUTABLE_VERSION_CONFLICT"):
        workflow.stage_batch(database, drafts[:2], version, apply=True)

    request = json.loads((REPO / "datasets/exploration-ai-contract/cases.json").read_text(encoding="utf-8"))["adapterCases"][0]["expectedRequest"]
    request["input"]["candidateNames"] = [packet["majorName"] for packet in staged["documents"]]
    request["input"]["targetName"] = None
    request["inputHash"] = input_hash(request)

    for count, packet in enumerate(staged["documents"]):
        with Session(database) as session:
            assert ExplorationEvidenceBinder(session, EvidencePolicy(version)).prepare(request).status == "INSUFFICIENT_EVIDENCE"
        receipt = review.preview(database, packet["documentId"], version)["receiptTemplate"]
        assert receipt["bindingManifest"] == packet["bindingManifest"]
        receipt.update(decision="APPROVED", reviewerId="SYNTHETIC_TEST_REVIEWER",
                       reviewedAt=datetime.now(timezone.utc).isoformat(),
                       sourceReference="SYNTHETIC_TEST_ONLY")
        review.record(database, receipt, apply=True)
        assert counts(database) == [3, 6, count + 1, 0]
    with Session(database) as session:
        assert ExplorationEvidenceBinder(session, EvidencePolicy(version)).prepare(request).status == "BOUND"


def test_manifest_failure_rolls_back_entire_batch(database, monkeypatch):
    manifest = workflow._manifest

    def fail_on_second(doc, chunks):
        if doc.major_name == "국사학과":
            raise RuntimeError("SYNTHETIC_BATCH_MANIFEST_FAILURE")
        return manifest(doc, chunks)

    monkeypatch.setattr(workflow, "_manifest", fail_on_second)
    with pytest.raises(RuntimeError, match="SYNTHETIC_BATCH_MANIFEST_FAILURE"):
        workflow.stage_batch(database, batch_drafts(), "EXPLORATION_RAG_TEST_ROLLBACK", apply=True)
    assert counts(database) == [0, 0, 0, 0]
