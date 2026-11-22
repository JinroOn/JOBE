"""All decisions here are synthetic and confined to disposable PostgreSQL."""
from copy import deepcopy
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
from threading import Barrier

import pytest
from sqlalchemy import text
from sqlalchemy.orm import Session

from app.exploration_contract import input_hash
from app.rag.exploration_binding import EvidenceIntegrityError, EvidencePolicy, ExplorationEvidenceBinder
from scripts import review_rag_evidence as review
from scripts import stage_rag_review as staging
from scripts.verify_rag_backup_migration import VerificationFault
from test_stage_rag_review import database, DRAFT, RELATIVE


def receipt(engine, decision="APPROVED"):
    packet = staging.stage(engine, DRAFT, RELATIVE, apply=False)
    value = review.preview(engine, packet["documentId"], packet["datasetVersion"])["receiptTemplate"]
    value.update(decision=decision, reviewerId="SYNTHETIC_TEST_REVIEWER",
                 reviewedAt=datetime.now(timezone.utc).isoformat(), sourceReference="SYNTHETIC_TEST_ONLY / verified source fields")
    return value


@pytest.fixture
def staged(database):
    staging.stage(database, DRAFT, RELATIVE, apply=True)
    return database


def count(engine):
    with engine.connect() as c:
        return c.scalar(text("SELECT count(*) FROM ai_evidence_reviews"))


def prepared(engine):
    request = json.loads((Path(__file__).resolve().parents[2] / "datasets/exploration-ai-contract/cases.json")
                         .read_text(encoding="utf-8"))["adapterCases"][0]["expectedRequest"]
    request["input"]["candidateNames"] = [DRAFT["majorName"]]
    request["input"]["targetName"] = None
    request["inputHash"] = input_hash(request)
    with Session(engine) as s:
        return ExplorationEvidenceBinder(s, EvidencePolicy(DRAFT["proposedDatasetVersion"])).prepare(request)


def test_preview_and_validation_do_not_approve(staged):
    value = receipt(staged)
    result = review.record(staged, value)
    assert result["operation"] == "WOULD_APPEND" and result["reviewId"] is None
    assert count(staged) == 0
    assert prepared(staged).status == "INSUFFICIENT_EVIDENCE"
    value["reviewerId"] = None
    with pytest.raises(VerificationFault, match="ACTUAL_REVIEWER_REQUIRED"):
        review.record(staged, value, apply=True)


def test_approval_replay_revoke_old_replay_and_reapproval(staged):
    approved = receipt(staged)
    first = review.record(staged, approved, apply=True)
    assert first["operation"] == "APPENDED"
    assert review.record(staged, approved, apply=True)["reviewId"] == first["reviewId"]
    assert count(staged) == 1 and prepared(staged).status == "BOUND"
    # Build a fresh receipt from the current manifest/latest scoped review.
    packet = review.preview(staged, approved["documentId"], approved["datasetVersion"])
    revoked = packet["receiptTemplate"]
    revoked.update(decision="REVOKED", reviewerId="SYNTHETIC_TEST_REVIEWER",
                   reviewedAt=datetime.now(timezone.utc).isoformat(), sourceReference="SYNTHETIC_TEST_REVOCATION")
    second = review.record(staged, revoked, apply=True)
    assert second["reviewId"] > first["reviewId"] and prepared(staged).status == "INSUFFICIENT_EVIDENCE"
    with pytest.raises(VerificationFault, match="LATEST_REVIEW_CHANGED"):
        review.record(staged, approved, apply=True)
    restored = review.preview(staged, approved["documentId"], approved["datasetVersion"])["receiptTemplate"]
    restored.update(decision="APPROVED", reviewerId="SYNTHETIC_TEST_REVIEWER",
                    reviewedAt=datetime.now(timezone.utc).isoformat(), sourceReference="SYNTHETIC_TEST_NEW_REVIEW")
    third = review.record(staged, restored, apply=True)
    assert third["reviewId"] > second["reviewId"] and count(staged) == 3
    assert prepared(staged).status == "BOUND"


def test_rejected_review_does_not_bind(staged):
    result = review.record(staged, receipt(staged, "REJECTED"), apply=True)
    assert result["decision"] == "REJECTED" and count(staged) == 1
    assert prepared(staged).status == "INSUFFICIENT_EVIDENCE"


@pytest.mark.parametrize("table,assignment", [
    ("ai_major_chunks", "chunk_text='Changed actual text, same declared hash'"),
    ("ai_major_documents", "metadata_json=metadata_json || jsonb_build_object('changed',true)"),
])
def test_content_change_rejects_receipt_before_appending(staged, table, assignment):
    value = receipt(staged)
    with staged.begin() as c:
        c.execute(text(f"UPDATE {table} SET {assignment}"))
    with pytest.raises(VerificationFault, match="REVIEWED_CONTENT_CHANGED"):
        review.record(staged, value, apply=True)
    assert count(staged) == 0


def test_invalid_chunk_hash_fails_closed(staged):
    value = receipt(staged)
    with staged.begin() as c:
        c.execute(text("UPDATE ai_major_chunks SET content_hash='invalid'"))
    with pytest.raises(EvidenceIntegrityError):
        review.record(staged, value, apply=True)
    assert count(staged) == 0


def test_competing_review_wins_over_stale_approval(staged):
    first = receipt(staged)
    second = deepcopy(first)
    second["reviewerId"] = "SYNTHETIC_OTHER_REVIEWER"
    review.record(staged, second, apply=True)
    with pytest.raises(VerificationFault, match="LATEST_REVIEW_CHANGED"):
        review.record(staged, first, apply=True)
    assert count(staged) == 1


def test_concurrent_decisions_append_exactly_one_latest_review(staged):
    first = receipt(staged)
    second = deepcopy(first)
    second["reviewerId"] = "SYNTHETIC_OTHER_REVIEWER"
    barrier = Barrier(2)
    def run(value):
        barrier.wait(timeout=10)
        try:
            return review.record(staged, value, apply=True)["operation"]
        except VerificationFault as error:
            return error.code
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(run, [first, second]))
    assert sorted(results) == ["APPENDED", "LATEST_REVIEW_CHANGED"]
    assert count(staged) == 1


def test_future_and_backdated_decisions_are_rejected(staged):
    value = receipt(staged)
    value["reviewedAt"] = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()
    with pytest.raises(VerificationFault, match="FUTURE_REVIEW_TIME"):
        review.record(staged, value, apply=True)
    value["reviewedAt"] = datetime.now(timezone.utc).isoformat()
    first = review.record(staged, value, apply=True)
    value.update(expectedLatestReviewId=first["reviewId"], decision="REVOKED",
                 reviewedAt=(datetime.now(timezone.utc) - timedelta(hours=1)).isoformat())
    with pytest.raises(VerificationFault, match="REVIEW_TIME_PRECEDES_LATEST"):
        review.record(staged, value, apply=True)
    assert count(staged) == 1


def test_flush_failure_rolls_back_review(staged, monkeypatch):
    value = receipt(staged)
    original = Session.flush
    def fail(self, *args, **kwargs):
        if self.new:
            raise RuntimeError("SYNTHETIC_FLUSH_FAILURE")
        return original(self, *args, **kwargs)
    monkeypatch.setattr(Session, "flush", fail)
    with pytest.raises(RuntimeError, match="SYNTHETIC_FLUSH_FAILURE"):
        review.record(staged, value, apply=True)
    assert count(staged) == 0


@pytest.mark.parametrize("field,value,code", [
    ("reviewerId", " ", "ACTUAL_REVIEWER_REQUIRED"),
    ("decision", None, "EXPLICIT_DECISION_REQUIRED"),
    ("sourceReference", "", "SOURCE_REVIEW_REFERENCE_REQUIRED"),
    ("reviewedAt", None, "ACTUAL_REVIEW_TIME_REQUIRED"),
    ("reviewedAt", "2026-11-18T00:00:00", "TIMEZONE_REQUIRED"),
    ("documentId", True, "INVALID_REVIEW_IDS"),
])
def test_incomplete_receipts_cannot_become_decisions(field, value, code):
    receipt_value = dict(schemaVersion=review.SCHEMA, documentId=1, datasetVersion="TEST_V1", scope="DEV",
                        contentHash="a" * 64, bindingManifest={}, expectedLatestReviewId=None,
                        decision="APPROVED", reviewerId="SYNTHETIC_REVIEWER",
                        reviewedAt=datetime.now(timezone.utc).isoformat(), sourceReference="SYNTHETIC")
    receipt_value[field] = value
    with pytest.raises(VerificationFault, match=code):
        review.validate_receipt(receipt_value)
