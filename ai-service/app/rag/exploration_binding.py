"""Read-only DEV evidence binding; no route, provider, approvals, retrieval logs, or job writes.

The caller is an authenticated server consumer of Java's owned, sealed context adapter.
An immutable binding is not generation authorization. Revalidate before use and final save;
READ COMMITTED gives statement-time checks, not a lock across an external provider call.
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import asdict, dataclass
from datetime import datetime
import hashlib
import json
import re

from sqlalchemy import func, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session
from psycopg import Error as PsycopgError

from app.exploration_contract import ContractViolation, input_hash, read_request
from app.rag.approval import AiEvidenceReview
from app.rag.ingestion import normalize_major_key
from app.rag.models import AiMajorChunk, AiMajorDocument

MANIFEST_VERSION = "EXPLORATION_REVIEW_MANIFEST_V1_0"
BINDING_VERSION = "EXPLORATION_EVIDENCE_BINDING_V1_0"
ALLOWED_CHUNKS = {"description": 0, "major_overview": 1, "related_jobs": 2}
ALLOWED_SOURCES = {"service_json": 0, "rag_jsonl": 1, "manual": 2, "public_data": 3}
CURRICULUM_QUESTION = re.compile(
    r"교과\s*과정|교육\s*과정|과목|수업|강의|학습\s*(?:예시|사례)|(?:무엇을|뭘|어떤\s*걸)\s*배우"
    r"|\b(?:courses?|curricul(?:um|a)|syllab(?:us|i)|learning examples?)\b",
    re.IGNORECASE,
)


class EvidencePolicyError(ValueError):
    def __init__(self): super().__init__("Invalid exploration evidence policy")


class EvidenceIntegrityError(ValueError):
    def __init__(self): super().__init__("Exploration evidence integrity check failed")


class EvidenceNoLongerAvailable(ValueError):
    def __init__(self): super().__init__("Pinned exploration evidence is no longer approved")


class EvidenceStoreUnavailable(RuntimeError):
    def __init__(self): super().__init__("Exploration evidence store unavailable")


def _json(value) -> str:
    try:
        return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    except (TypeError, ValueError):
        raise EvidenceIntegrityError() from None


def _hash(value) -> str:
    try:
        return hashlib.sha256(_json(value).encode("utf-8")).hexdigest()
    except UnicodeError:
        raise EvidenceIntegrityError() from None


@dataclass(frozen=True)
class EvidencePolicy:
    """Server-pinned configuration; never read dataset/scope from client evidence or a latest-version query."""
    dataset_version: str
    scope: str = "DEV"
    version: str = BINDING_VERSION

    def __post_init__(self):
        if (not isinstance(self.dataset_version, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}", self.dataset_version)
                or self.scope != "DEV" or self.version != BINDING_VERSION):
            raise EvidencePolicyError()


@dataclass(frozen=True)
class EvidenceBinding:
    original_request_json: str
    request_json: str
    references_json: str
    policy_json: str
    binding_hash: str

    @property
    def request(self) -> dict: return json.loads(self.request_json)

    @property
    def references(self) -> list[dict]: return json.loads(self.references_json)

    @property
    def generation_authorized(self) -> bool: return False

    def _model_evidence(self) -> list[dict]:
        # IDs, source paths, review notes and arbitrary source metadata are for internal audit only.
        return [{"referenceKey": ref["referenceKey"], "majorName": ref["majorName"],
                 "chunkType": ref["chunkType"], "text": ref["text"]} for ref in self.references]


@dataclass(frozen=True)
class PreparedEvidence:
    status: str
    blocked_reason: str | None
    request_json: str
    binding: EvidenceBinding | None

    @property
    def request(self) -> dict: return json.loads(self.request_json)

    @property
    def generation_authorized(self) -> bool: return False


def _manifest(document: AiMajorDocument, chunks: list[AiMajorChunk]) -> dict:
    if not chunks or len(chunks) > 256 or not re.fullmatch(r"[a-f0-9]{64}", document.content_hash):
        raise EvidenceIntegrityError()
    doc = {"id": document.id, "majorKey": document.major_key, "majorName": document.major_name,
           "standardMajorName": document.standard_major_name, "sourceType": document.source_type,
           "sourcePath": document.source_path, "datasetVersion": document.dataset_version,
           "declaredHash": document.content_hash, "metadata": document.metadata_json}
    entries = []
    for chunk in sorted(chunks, key=lambda c: c.id):
        if (not re.fullmatch(r"[a-f0-9]{64}", chunk.content_hash) or chunk.document_id != document.id
                or chunk.major_key != document.major_key or chunk.major_name != document.major_name
                or chunk.source_type != document.source_type):
            raise EvidenceIntegrityError()
        data = {"id": chunk.id, "documentId": chunk.document_id, "majorKey": chunk.major_key, "majorName": chunk.major_name,
                "index": chunk.chunk_index, "type": chunk.chunk_type, "sourceType": chunk.source_type,
                "declaredHash": chunk.content_hash, "text": chunk.chunk_text, "metadata": chunk.metadata_json}
        entries.append({"chunkId": chunk.id, "fingerprint": _hash(data)})
    return {"version": MANIFEST_VERSION, "documentFingerprint": _hash(doc), "chunks": entries}


class ExplorationEvidenceBinder:
    def __init__(self, session: Session, policy: EvidencePolicy):
        self.session = session
        self.policy = policy

    def _rows(self, *, keys: list[str] | None = None, ids: list[int] | None = None):
        try:
            with self.session.no_autoflush:
                if (self.session.get_bind().dialect.name != "postgresql"
                        or self.session.connection().get_isolation_level() != "READ COMMITTED"):
                    raise EvidencePolicyError()
                latest = (select(func.max(AiEvidenceReview.id)).where(
                    AiEvidenceReview.document_id == AiMajorDocument.id, AiEvidenceReview.scope == self.policy.scope)
                    .correlate(AiMajorDocument).scalar_subquery())
                statement = (select(AiMajorDocument, AiMajorChunk, AiEvidenceReview, func.statement_timestamp()).select_from(AiMajorDocument)
                             .outerjoin(AiMajorChunk, AiMajorChunk.document_id == AiMajorDocument.id)
                             .outerjoin(AiEvidenceReview, AiEvidenceReview.id == latest)
                             .where(AiMajorDocument.dataset_version == self.policy.dataset_version))
                if keys is not None: statement = statement.where(AiMajorDocument.major_key.in_(keys))
                if ids is not None: statement = statement.where(AiMajorDocument.id.in_(ids))
                # One statement snapshot, with current rows replacing SQLAlchemy's identity-map cache.
                return list(self.session.execute(statement.order_by(AiMajorDocument.id, AiMajorChunk.id)
                                                 .execution_options(populate_existing=True)))
        except (SQLAlchemyError, PsycopgError):
            raise EvidenceStoreUnavailable() from None

    def capture_review_manifest(self, document_id: int) -> dict:
        """Preview attestation for an authorized human review workflow. Does not insert/approve/backfill anything."""
        if type(document_id) is not int or document_id <= 0: raise EvidencePolicyError()
        rows = self._rows(ids=[document_id])
        if not rows: raise EvidenceIntegrityError()
        return _manifest(rows[0][0], [row[1] for row in rows if row[1] is not None])

    def _eligible(self, doc, review, now: datetime) -> bool:
        return (review is not None and review.scope == self.policy.scope and review.decision == "APPROVED"
                and review.dataset_version == doc.dataset_version and review.content_hash == doc.content_hash
                and review.content_status in {"REVIEWED", "ACTIVE"}
                and review.implementation_status == "APPROVED_FOR_IMPLEMENTATION" and review.domain_status == "APPROVED"
                and bool(review.reviewer_id.strip()) and bool(review.source_reference.strip())
                and bool(doc.source_path and doc.source_path.strip())
                and review.reviewed_at is not None and review.reviewed_at <= now
                and review.binding_manifest is not None)

    def _approved_documents(self, rows):
        grouped = defaultdict(list)
        for row in rows: grouped[row[0].id].append(row)
        approved = []
        for group in grouped.values():
            doc, _, review, now = group[0]
            if not self._eligible(doc, review, now): continue
            chunks = [row[1] for row in group if row[1] is not None]
            manifest = _manifest(doc, chunks)
            if manifest != review.binding_manifest: raise EvidenceIntegrityError()
            if doc.major_key != normalize_major_key(doc.standard_major_name or doc.major_name): raise EvidenceIntegrityError()
            approved.append((doc, chunks, review, manifest))
        return approved

    @staticmethod
    def _subjects(request: dict) -> list[str]:
        return [request["input"]["targetName"]] if request["input"]["targetName"] else request["input"]["candidateNames"]

    def _reference(self, doc, chunk, review, manifest, name) -> dict:
        if chunk.chunk_type not in ALLOWED_CHUNKS or chunk.source_type not in ALLOWED_SOURCES or not chunk.chunk_text.strip():
            raise EvidenceIntegrityError()
        fingerprint = next(c["fingerprint"] for c in manifest["chunks"] if c["chunkId"] == chunk.id)
        identity = {"datasetVersion": doc.dataset_version, "documentId": doc.id, "chunkId": chunk.id,
                    "reviewId": review.id, "documentFingerprint": manifest["documentFingerprint"], "chunkFingerprint": fingerprint}
        excerpt = chunk.chunk_text[:1600]
        return {**identity, "referenceKey": "ref_" + _hash(identity), "majorName": name, "sourceMajorName": doc.major_name,
                "documentContentHash": doc.content_hash, "chunkContentHash": chunk.content_hash,
                "scope": review.scope, "chunkType": chunk.chunk_type, "sourceType": doc.source_type,
                "sourcePath": doc.source_path, "sourceReference": review.source_reference,
                "text": excerpt, "excerptHash": _hash(excerpt)}

    def prepare(self, request: dict) -> PreparedEvidence:
        original = read_request(request).model_dump()
        if original["evidence"]["bindingStatus"] != "NOT_BOUND": raise ContractViolation("Invalid exploration AI contract")
        subjects = self._subjects(original)
        if not subjects:
            return PreparedEvidence("INSUFFICIENT_EVIDENCE", "APPROVED_EVIDENCE_UNAVAILABLE", _json(original), None)
        keys = [normalize_major_key(name) for name in subjects]
        if any(not key for key in keys) or len(set(keys)) != len(keys): raise EvidencePolicyError()
        approved = self._approved_documents(self._rows(keys=keys))
        conversation = original["input"].get("conversation")
        prefer_curriculum = bool(conversation and CURRICULUM_QUESTION.search(conversation["question"]))
        refs = []
        for name, key in zip(subjects, keys):
            candidates = [(doc, chunk, review, manifest) for doc, chunks, review, manifest in approved if doc.major_key == key
                          for chunk in chunks if chunk.chunk_type in ALLOWED_CHUNKS and chunk.source_type in ALLOWED_SOURCES and chunk.chunk_text.strip()]
            if not candidates:
                return PreparedEvidence("INSUFFICIENT_EVIDENCE", "APPROVED_EVIDENCE_UNAVAILABLE", _json(original), None)
            selected = min(candidates, key=lambda row: (
                prefer_curriculum and row[1].chunk_type != "major_overview",
                ALLOWED_SOURCES[row[0].source_type], ALLOWED_CHUNKS[row[1].chunk_type],
                row[0].id, row[1].chunk_index, row[1].id,
            ))
            refs.append(self._reference(*selected, name))
        bound = json.loads(_json(original))
        bound["evidence"] = {"approvedOnly": True, "bindingStatus": "BOUND", "datasetVersion": self.policy.dataset_version,
                             "referenceKeys": [ref["referenceKey"] for ref in refs]}
        bound["inputHash"] = input_hash(bound)
        read_request(bound)
        policy = asdict(self.policy)
        digest = _hash({"original": original, "request": bound, "policy": policy, "references": refs})
        binding = EvidenceBinding(_json(original), _json(bound), _json(refs), _json(policy), digest)
        return PreparedEvidence("BOUND", None, binding.request_json, binding)

    def revalidate(self, binding: EvidenceBinding) -> bool:
        """Re-read exact pinned IDs/latest scoped reviews. No new selection, approval, or fallback."""
        try:
            original = read_request(json.loads(binding.original_request_json)).model_dump()
            request = read_request(binding.request).model_dump()
            refs = binding.references
            policy = json.loads(binding.policy_json)
            if not isinstance(refs, list) or not 1 <= len(refs) <= 8: raise EvidenceIntegrityError()
            for ref in refs:
                if not isinstance(ref, dict) or any(type(ref.get(key)) is not int or ref[key] <= 0 for key in ("documentId", "chunkId", "reviewId")):
                    raise EvidenceIntegrityError()
            if (policy != asdict(self.policy) or original["evidence"]["bindingStatus"] != "NOT_BOUND"
                    or request["evidence"]["bindingStatus"] != "BOUND" or request["evidence"]["datasetVersion"] != self.policy.dataset_version
                    or _hash({"original": original, "request": request, "policy": policy, "references": refs}) != binding.binding_hash):
                raise EvidenceIntegrityError()
            for key in original:
                if key not in {"evidence", "inputHash"} and original[key] != request[key]: raise EvidenceIntegrityError()
            if (request["evidence"]["referenceKeys"] != [ref["referenceKey"] for ref in refs]
                    or [ref["majorName"] for ref in refs] != self._subjects(original)):
                raise EvidenceIntegrityError()
            docs = {doc.id: (doc, chunks, review, manifest) for doc, chunks, review, manifest in self._approved_documents(
                self._rows(ids=list({ref["documentId"] for ref in refs})))}
            for ref in refs:
                current = docs.get(ref["documentId"])
                if current is None or current[2].id != ref["reviewId"]: raise EvidenceNoLongerAvailable()
                doc, chunks, review, manifest = current
                chunk = next((c for c in chunks if c.id == ref["chunkId"]), None)
                if chunk is None: raise EvidenceNoLongerAvailable()
                if self._reference(doc, chunk, review, manifest, ref["majorName"]) != ref: raise EvidenceIntegrityError()
            return True
        except (KeyError, TypeError, json.JSONDecodeError, AttributeError):
            raise EvidenceIntegrityError() from None

    def validated_model_evidence(self, binding: EvidenceBinding) -> list[dict]:
        """A fresh approval/content check is required before obtaining the model's fact allowlist."""
        self.revalidate(binding)
        return binding._model_evidence()
