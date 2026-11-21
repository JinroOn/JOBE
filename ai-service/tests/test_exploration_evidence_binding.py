"""All approval records here are synthetic, in migrated disposable PostgreSQL only."""
from copy import deepcopy
from dataclasses import replace
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import uuid

import pytest
from sqlalchemy import create_engine, delete, func, select, update
from sqlalchemy.exc import DBAPIError, OperationalError
from sqlalchemy.orm import Session

from app.exploration_contract import CONVERSATION_VERSION, ContractViolation, input_hash, read_request, validate_response
from app.rag.approval import AiEvidenceReview
from app.rag.exploration_binding import (
    EvidenceIntegrityError, EvidenceNoLongerAvailable, EvidencePolicy, EvidencePolicyError,
    EvidenceStoreUnavailable, ExplorationEvidenceBinder,
)
from app.rag.ingestion import normalize_major_key, stable_hash
from app.rag.models import AiMajorAlias, AiMajorChunk, AiMajorDocument, AiRetrievalLog
from app.rag.retrieval import MajorRagRetriever
from app.rag.schemas import RetrievalRequest

CASES = json.loads((Path(__file__).resolve().parents[2] / "datasets/exploration-ai-contract/cases.json").read_text(encoding="utf-8"))
VERSION = "TEST_BINDING_V1"


@pytest.fixture
def db():
    url = os.getenv("SECTION32_TEST_DATABASE_URL")
    if not url: pytest.skip("Set SECTION32_TEST_DATABASE_URL to migrated disposable PostgreSQL")
    engine = create_engine(url)
    with engine.connect() as connection:
        tx = connection.begin()
        with Session(bind=connection) as session: yield session
        if tx.is_active: tx.rollback()
    engine.dispose()


def request(names, *, purpose="CONSULTATION", target=None):
    value = deepcopy(CASES["adapterCases"][0]["expectedRequest"])
    value["purpose"] = purpose
    value["input"]["candidateNames"] = names
    value["input"]["targetName"] = target
    value["input"]["diagnosisStatus"] = "READY" if names else "NO_SUPPORTED_CANDIDATE"
    value["input"]["uncertainty"]["lowEvidence"] = False
    value["evidence"] = {"approvedOnly": True, "bindingStatus": "NOT_BOUND", "datasetVersion": None, "referenceKeys": []}
    value["inputHash"] = input_hash(value)
    read_request(value)
    return value


def document(db, *, name=None, version=VERSION, chunk_type="description", source_type="public_data"):
    name = name or "TEST_전공_" + uuid.uuid4().hex
    doc = AiMajorDocument(major_key=normalize_major_key(name), major_name=name, standard_major_name=name,
                          source_type=source_type, source_path="https://example.invalid/official-curriculum",
                          dataset_version=version, content_hash=stable_hash({"fixture": uuid.uuid4().hex}),
                          metadata_json={"coreAxes": {"legacy-private-metric": 99}})
    db.add(doc); db.flush()
    chunk = AiMajorChunk(document_id=doc.id, major_key=doc.major_key, major_name=name, chunk_index=0,
                         chunk_type=chunk_type, source_type=source_type, chunk_text="TEST ONLY: reviewed educational overview",
                         content_hash=stable_hash({"fixtureChunk": uuid.uuid4().hex}), embedding_model="TEST_ONLY", embedding=None,
                         metadata_json={"scores": [99], "privateMetadata": "DO_NOT_FORWARD"})
    db.add(chunk); db.flush()
    return doc, chunk


def review(db, doc, *, manifest=True, **overrides):
    witness = ExplorationEvidenceBinder(db, EvidencePolicy(doc.dataset_version)).capture_review_manifest(doc.id) if manifest else None
    values = dict(document_id=doc.id, dataset_version=doc.dataset_version, content_hash=doc.content_hash,
                  scope="DEV", decision="APPROVED", content_status="REVIEWED", implementation_status="APPROVED_FOR_IMPLEMENTATION",
                  domain_status="APPROVED", reviewer_id="SYNTHETIC_TEST_REVIEWER", source_reference="SYNTHETIC_TEST_EVIDENCE",
                  binding_manifest=witness)
    values.update(overrides)
    value = AiEvidenceReview(**values); db.add(value); db.flush()
    return value


def binder(db): return ExplorationEvidenceBinder(db, EvidencePolicy(VERSION))


def test_major_explanation_requires_approved_educational_evidence(db):
    jobs, _ = document(db, chunk_type="related_jobs")
    curriculum, curriculum_chunk = document(db, name=jobs.major_name, chunk_type="major_overview")
    review(db, jobs)
    value = deepcopy(next(case["request"] for case in CASES["requestCases"]
                          if case["name"] == "major_explanation_bound"))
    value["input"]["targetName"] = jobs.major_name
    value["evidence"] = {"approvedOnly": True, "bindingStatus": "NOT_BOUND", "datasetVersion": None, "referenceKeys": []}
    value["inputHash"] = input_hash(value)

    evidence_binder = binder(db)
    assert evidence_binder.prepare(value).status == "INSUFFICIENT_EVIDENCE"
    review(db, curriculum)
    prepared = evidence_binder.prepare(value)
    assert [reference["chunkId"] for reference in prepared.binding.references] == [curriculum_chunk.id]
    assert evidence_binder.revalidate(prepared.binding)


@pytest.mark.parametrize(("question", "overview_approved", "expected_type"), [
    ("제공된 자료에서 국사학과와 관련한 학습 예시를 하나만 알려 주세요.", True, "major_overview"),
    ("교육 과정에서 어떤 교과목을 배우나요?", True, "major_overview"),
    ("이 전공의 특징을 알려 주세요.", True, "description"),
    ("학습 예시를 알려 주세요.", False, "description"),
])
def test_consultation_selects_approved_evidence_for_current_question(db, question, overview_approved, expected_type):
    description, default_chunk = document(db, source_type="service_json")
    overview, curriculum_chunk = document(db, name=description.major_name, chunk_type="major_overview")
    review(db, description)
    if overview_approved:
        review(db, overview)
    name = description.major_name
    legacy_request = request([name], target=name)
    evidence_binder = binder(db)
    saved = evidence_binder.prepare(legacy_request).binding
    value = deepcopy(legacy_request)
    value["contractVersion"] = CONVERSATION_VERSION
    value["input"]["conversation"] = {
        "history": [{"role": "user", "content": "전공 수업을 알려 주세요."},
                    {"role": "assistant", "content": "이전 상담 답변입니다."}],
        "question": question,
    }
    value["inputHash"] = input_hash(value)

    prepared = evidence_binder.prepare(value).binding
    expected_chunk = curriculum_chunk if expected_type == "major_overview" else default_chunk
    assert [ref["chunkId"] for ref in prepared.references] == [expected_chunk.id]
    assert evidence_binder.validated_model_evidence(prepared)[0]["chunkType"] == expected_type
    assert [ref["chunkId"] for ref in saved.references] == [default_chunk.id]
    assert evidence_binder.revalidate(saved)


@pytest.mark.parametrize("values", [{"dataset_version": ""}, {"dataset_version": " latest "}, {"dataset_version": "x" * 81},
                                     {"dataset_version": VERSION, "scope": "PUBLIC"}, {"dataset_version": VERSION, "scope": "PILOT"},
                                     {"dataset_version": VERSION, "version": "other"}])
def test_fixed_dev_server_policy_is_required(values):
    with pytest.raises(EvidencePolicyError): EvidencePolicy(**values)


def test_real_database_binding_changes_only_evidence_hash_and_freezes_auditable_references(db):
    doc, chunk = document(db); approval = review(db, doc)
    value = request([doc.major_name]); original = deepcopy(value)
    before = db.scalar(select(func.count()).select_from(AiRetrievalLog))
    prepared = binder(db).prepare(value)
    assert prepared.status == "BOUND" and prepared.blocked_reason is None
    assert not prepared.generation_authorized and not prepared.binding.generation_authorized
    assert value == original and prepared.request["input"] == original["input"]
    assert prepared.request["sourceContextHash"] == original["sourceContextHash"]
    assert prepared.request["inputHash"] != original["inputHash"]
    assert read_request(prepared.request).evidence.bindingStatus == "BOUND"
    ref = prepared.binding.references[0]
    assert (ref["reviewId"], ref["documentId"], ref["chunkId"]) == (approval.id, doc.id, chunk.id)
    assert ref["documentContentHash"] == doc.content_hash and ref["chunkContentHash"] == chunk.content_hash
    assert ref["sourcePath"] == doc.source_path and ref["sourceReference"] == approval.source_reference
    assert len(ref["chunkFingerprint"]) == len(ref["documentFingerprint"]) == 64
    assert binder(db).revalidate(prepared.binding)
    assert binder(db).prepare(value).binding == prepared.binding
    assert db.scalar(select(func.count()).select_from(AiRetrievalLog)) == before
    # Shared wire response validation is still necessary and is not a provider invocation.
    response = {k: prepared.request[k] for k in ["contractVersion", "requestId", "inputHash", "purpose"]}
    response.update(status="SUCCEEDED", changesRecommendation=False, reasonCode=None,
                    result={"summary": "Synthetic contract only", "questions": [], "referenceKeys": [ref["referenceKey"]]})
    assert validate_response(prepared.request, response).status == "SUCCEEDED"


def test_model_evidence_is_an_allowlist_and_returned_values_cannot_modify_the_binding(db):
    doc, _ = document(db); review(db, doc)
    prepared = binder(db).prepare(request([doc.major_name]))
    fact = binder(db).validated_model_evidence(prepared.binding)[0]
    assert set(fact) == {"referenceKey", "majorName", "chunkType", "text"}
    assert "coreAxes" not in json.dumps(fact) and "DO_NOT_FORWARD" not in json.dumps(fact)
    prepared.request["evidence"]["referenceKeys"].clear()
    prepared.binding.references[0]["text"] = "changed locally"
    assert binder(db).revalidate(prepared.binding)


@pytest.mark.parametrize("overrides", [
    {"scope": "PUBLIC", "content_status": "ACTIVE"}, {"scope": "PILOT"}, {"decision": "REJECTED"}, {"decision": "REVOKED"},
    {"content_status": "DRAFT"}, {"content_status": "ARCHIVED"}, {"implementation_status": "PENDING"},
    {"domain_status": "PENDING"}, {"domain_status": "REJECTED"}, {"reviewer_id": " "}, {"source_reference": " "},
    {"content_hash": "a" * 64}, {"dataset_version": "OTHER_VERSION"},
    {"reviewed_at": datetime.now(timezone.utc) + timedelta(days=10)},
])
def test_wrong_scope_status_evidence_hash_version_or_future_review_never_binds(db, overrides):
    doc, _ = document(db); review(db, doc, **overrides)
    result = binder(db).prepare(request([doc.major_name]))
    assert result.status == "INSUFFICIENT_EVIDENCE" and result.binding is None
    assert result.request["evidence"]["bindingStatus"] == "NOT_BOUND"


def test_missing_review_and_legacy_unattested_review_remain_unavailable_without_backfill(db):
    doc, _ = document(db)
    assert binder(db).prepare(request([doc.major_name])).binding is None
    old = review(db, doc, manifest=False)
    assert binder(db).prepare(request([doc.major_name])).binding is None
    db.refresh(old); assert old.binding_manifest is None


@pytest.mark.parametrize("path", [None, " "])
def test_missing_source_is_not_approved_evidence(db, path):
    doc, _ = document(db); doc.source_path = path; db.flush(); review(db, doc)
    assert binder(db).prepare(request([doc.major_name])).binding is None


def test_latest_rejection_prevents_old_approval_fallback_and_revalidation(db):
    doc, _ = document(db); review(db, doc)
    saved = binder(db).prepare(request([doc.major_name])).binding
    review(db, doc, decision="REVOKED", manifest=False)
    assert binder(db).prepare(request([doc.major_name])).binding is None
    with pytest.raises(EvidenceNoLongerAvailable): binder(db).revalidate(saved)
    with pytest.raises(EvidenceNoLongerAvailable): binder(db).validated_model_evidence(saved)


def test_new_review_requires_explicit_rebinding_and_does_not_rewrite_pinned_references(db):
    doc, _ = document(db); first = review(db, doc)
    saved = binder(db).prepare(request([doc.major_name])).binding
    second = review(db, doc)
    with pytest.raises(EvidenceNoLongerAvailable): binder(db).revalidate(saved)
    assert saved.references[0]["reviewId"] == first.id
    assert binder(db).prepare(request([doc.major_name])).binding.references[0]["reviewId"] == second.id


@pytest.mark.parametrize("change", ["text", "chunk_hash", "metadata", "source", "document_metadata", "extra_chunk"])
def test_review_manifest_detects_content_changes_even_if_declared_hash_is_recomputed(db, change):
    doc, chunk = document(db); review(db, doc)
    saved = binder(db).prepare(request([doc.major_name])).binding
    if change == "text": db.execute(update(AiMajorChunk).where(AiMajorChunk.id == chunk.id).values(chunk_text="tampered PRIVATE BODY"))
    elif change == "chunk_hash": db.execute(update(AiMajorChunk).where(AiMajorChunk.id == chunk.id).values(chunk_text="changed", content_hash=stable_hash("changed")))
    elif change == "metadata": db.execute(update(AiMajorChunk).where(AiMajorChunk.id == chunk.id).values(metadata_json={"changed": True}))
    elif change == "source": db.execute(update(AiMajorDocument).where(AiMajorDocument.id == doc.id).values(source_path="changed"))
    elif change == "document_metadata": db.execute(update(AiMajorDocument).where(AiMajorDocument.id == doc.id).values(metadata_json={"changed": True}))
    else:
        db.add(AiMajorChunk(document_id=doc.id, major_key=doc.major_key, major_name=doc.major_name, chunk_index=1,
                            chunk_type="description", source_type=doc.source_type, chunk_text="new unreviewed chunk",
                            content_hash=stable_hash("extra"), embedding_model="TEST_ONLY", metadata_json={}))
        db.flush()
    with pytest.raises(EvidenceIntegrityError, match="integrity check failed"):
        binder(db).prepare(request([doc.major_name]))
    with pytest.raises(EvidenceIntegrityError): binder(db).revalidate(saved)


def test_malformed_non_null_attestation_is_an_integrity_error_not_empty_evidence(db):
    doc, _ = document(db); review(db, doc, binding_manifest={"version": "forged"})
    with pytest.raises(EvidenceIntegrityError): binder(db).prepare(request([doc.major_name]))


def test_deleted_reviewed_chunks_are_an_integrity_failure_not_a_normal_empty_corpus(db):
    doc, chunk = document(db); review(db, doc)
    db.execute(delete(AiMajorChunk).where(AiMajorChunk.id == chunk.id))
    with pytest.raises(EvidenceIntegrityError): binder(db).prepare(request([doc.major_name]))


@pytest.mark.parametrize("chunk_type", ["core_axes", "keywords", "differentiation", "service_summary"])
def test_legacy_vectors_and_unstructured_summaries_are_not_new_exploration_facts(db, chunk_type):
    doc, _ = document(db, chunk_type=chunk_type); review(db, doc)
    assert binder(db).prepare(request([doc.major_name])).binding is None


def test_no_alias_vector_or_newest_dataset_fallback(db, monkeypatch):
    doc, _ = document(db); review(db, doc)
    alias = "TEST_ALIAS_" + uuid.uuid4().hex
    db.add(AiMajorAlias(alias_name=alias, normalized_alias=normalize_major_key(alias), major_key=doc.major_key,
                        major_name=doc.major_name, source="TEST_ONLY", confidence=1.0)); db.flush()
    monkeypatch.setattr(MajorRagRetriever, "retrieve", lambda *_: pytest.fail("Legacy retrieval must not be called"))
    assert binder(db).prepare(request([alias])).binding is None
    other, _ = document(db, version="NEWER_VERSION"); review(db, other)
    assert binder(db).prepare(request([other.major_name])).binding is None


def test_consultation_requires_each_named_subject_but_a_plan_only_needs_its_selected_target(db):
    a, _ = document(db); b, _ = document(db); review(db, a)
    assert binder(db).prepare(request([a.major_name, b.major_name])).binding is None
    result = binder(db).prepare(request([a.major_name, b.major_name], purpose="PLAN", target=a.major_name))
    assert result.status == "BOUND" and [ref["majorName"] for ref in result.binding.references] == [a.major_name]
    assert binder(db).revalidate(result.binding)


def test_all_eight_subjects_receive_stable_distinct_references(db):
    names = []
    for _ in range(8):
        doc, _ = document(db); review(db, doc); names.append(doc.major_name)
    result = binder(db).prepare(request(names))
    assert len(set(result.request["evidence"]["referenceKeys"])) == 8
    assert [ref["majorName"] for ref in result.binding.references] == names


def test_newer_corpus_or_another_document_does_not_rewrite_pinned_references(db):
    doc, _ = document(db); review(db, doc)
    saved = binder(db).prepare(request([doc.major_name])).binding
    newer, _ = document(db, name=doc.major_name, version="NEWER_VERSION"); review(db, newer)
    another, _ = document(db, name=doc.major_name); review(db, another)
    assert binder(db).revalidate(saved)
    assert saved.references[0]["documentId"] == doc.id
    assert saved.request["evidence"]["datasetVersion"] == VERSION


def test_preparation_never_autoflushes_a_pending_approval(db):
    doc, _ = document(db)
    service = binder(db)
    pending = AiEvidenceReview(document_id=doc.id, dataset_version=doc.dataset_version, content_hash=doc.content_hash,
                               scope="DEV", decision="APPROVED", content_status="REVIEWED",
                               implementation_status="APPROVED_FOR_IMPLEMENTATION", domain_status="APPROVED",
                               reviewer_id="SYNTHETIC_TEST_REVIEWER", source_reference="SYNTHETIC_TEST_EVIDENCE",
                               binding_manifest=service.capture_review_manifest(doc.id))
    db.add(pending)
    try:
        assert service.prepare(request([doc.major_name])).binding is None
        assert pending.id is None and pending in db.new
    finally: db.expunge(pending)


def test_no_candidate_does_not_invent_generic_corpus_or_run_search(db, monkeypatch):
    monkeypatch.setattr(db, "execute", lambda *_a, **_kw: pytest.fail("No subject corpus was selected"))
    result = binder(db).prepare(request([]))
    assert result.status == "INSUFFICIENT_EVIDENCE" and not result.generation_authorized


def test_client_claiming_bound_evidence_is_rejected_before_search(db, monkeypatch):
    value = request(["TEST"])
    value["evidence"] = {"approvedOnly": True, "bindingStatus": "BOUND", "datasetVersion": VERSION, "referenceKeys": ["forged"]}
    value["inputHash"] = input_hash(value)
    monkeypatch.setattr(db, "execute", lambda *_a, **_kw: pytest.fail("Client binding must not be trusted"))
    with pytest.raises(ContractViolation): binder(db).prepare(value)


def test_binding_mutation_and_a_different_policy_are_rejected(db):
    doc, _ = document(db); review(db, doc)
    saved = binder(db).prepare(request([doc.major_name])).binding
    with pytest.raises(EvidenceIntegrityError): binder(db).revalidate(replace(saved, binding_hash="0" * 64))
    with pytest.raises(EvidenceIntegrityError): ExplorationEvidenceBinder(db, EvidencePolicy("DIFFERENT")).revalidate(saved)


def test_database_outage_is_redacted_and_never_treated_as_missing_approval(db, monkeypatch):
    def failed(*_args, **_kwargs): raise OperationalError("PRIVATE SQL", {}, Exception("PRIVATE CREDENTIAL"))
    monkeypatch.setattr(db, "execute", failed)
    with pytest.raises(EvidenceStoreUnavailable) as error: binder(db).prepare(request(["TEST"]))
    assert "PRIVATE" not in str(error.value)


def test_stale_transaction_isolation_is_rejected_before_evidence_search():
    url = os.getenv("SECTION32_TEST_DATABASE_URL")
    if not url: pytest.skip("Set isolated PostgreSQL URL")
    engine = create_engine(url, isolation_level="REPEATABLE READ")
    try:
        with Session(engine) as session:
            with pytest.raises(EvidencePolicyError): binder(session).prepare(request(["TEST"]))
    finally: engine.dispose()


def test_dev_approvals_cannot_authorize_legacy_public_search(db):
    doc, _ = document(db); review(db, doc, content_status="ACTIVE")
    result = MajorRagRetriever(session=db).retrieve(RetrievalRequest(majorName=doc.major_name, datasetVersion=VERSION,
                                                                   approvedOnly=True, enableVectorSearch=False))
    assert result.ragSnippets == []


def test_manifest_is_covered_by_append_only_review_trigger(db):
    doc, _ = document(db); row = review(db, doc)
    with pytest.raises(DBAPIError, match="append-only"):
        with db.begin_nested(): db.execute(update(AiEvidenceReview).where(AiEvidenceReview.id == row.id).values(binding_manifest={}))


def test_read_committed_revalidation_observes_a_second_connections_revocation():
    url = os.getenv("SECTION32_TEST_DATABASE_URL")
    if not url: pytest.skip("Set isolated PostgreSQL URL")
    engine = create_engine(url)
    version = "TEST_COMMITTED_BINDING_" + uuid.uuid4().hex
    # Append-only audit rows deliberately remain in this disposable database until container disposal.
    try:
        with Session(engine) as writer:
            doc, _ = document(writer, version=version); review(writer, doc)
            name, doc_id = doc.major_name, doc.id
            writer.commit()
        with Session(engine) as reader:
            service = ExplorationEvidenceBinder(reader, EvidencePolicy(version))
            saved = service.prepare(request([name])).binding
            assert service.revalidate(saved)
            with Session(engine) as writer:
                doc = writer.get(AiMajorDocument, doc_id); review(writer, doc, decision="REVOKED", manifest=False); writer.commit()
            with pytest.raises(EvidenceNoLongerAvailable): service.revalidate(saved)
    finally: engine.dispose()
