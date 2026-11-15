from __future__ import annotations

import os
import uuid
import pytest
from pydantic import ValidationError
from sqlalchemy import create_engine, update
from sqlalchemy.exc import DBAPIError
from sqlalchemy.orm import Session
from app.rag.approval import AiEvidenceReview
from app.rag.models import AiMajorDocument, AiMajorChunk, AiMajorAlias
from app.rag.retrieval import MajorRagRetriever
from app.rag.schemas import RetrievalRequest


@pytest.fixture
def db():
    url=os.getenv("SECTION32_TEST_DATABASE_URL")
    if not url:
        pytest.skip("Set SECTION32_TEST_DATABASE_URL to a migrated isolated PostgreSQL database")
    engine=create_engine(url)
    with engine.connect() as connection:
        tx=connection.begin()
        with Session(bind=connection) as session:
            yield session
        tx.rollback()
    engine.dispose()


def document(db, name, index=0):
    doc=AiMajorDocument(major_key=name,major_name=name,source_type="public_data",dataset_version="approval-test-v1",
                        content_hash=uuid.uuid4().hex,source_path="https://example.edu/curriculum",metadata_json={})
    db.add(doc); db.flush()
    chunk=AiMajorChunk(document_id=doc.id,major_key=name,major_name=name,chunk_index=index,chunk_text="Reviewed educational subject",
                       chunk_type="description",source_type="public_data",content_hash=uuid.uuid4().hex,
                       embedding_model="test",embedding=[1.0]*1536,metadata_json={"sourcePriority":index})
    db.add(chunk); db.flush()
    return doc,chunk


def review(db,doc,**overrides):
    values=dict(document_id=doc.id,dataset_version=doc.dataset_version,content_hash=doc.content_hash,scope="PUBLIC",
                decision="APPROVED",content_status="ACTIVE",implementation_status="APPROVED_FOR_IMPLEMENTATION",
                domain_status="APPROVED",reviewer_id="test-only-reviewer",source_reference="test-only-evidence")
    values.update(overrides)
    row=AiEvidenceReview(**values); db.add(row); db.flush(); return row


def retrieve(db,name,**kwargs):
    return MajorRagRetriever(session=db).retrieve(RetrievalRequest(majorName=name,datasetVersion="approval-test-v1",
                                                               approvedOnly=True,enableVectorSearch=False,**kwargs))


def test_fixed_version_required_and_legacy_weakness_forbidden():
    with pytest.raises(ValidationError):
        RetrievalRequest(majorName="x",approvedOnly=True)
    with pytest.raises(ValidationError):
        RetrievalRequest(majorName="x",approvedOnly=True,datasetVersion="fixed",weaknessFocus=["math"])


def test_approval_filter_runs_before_top_k_limit_and_returns_trace(db):
    name="approval"+uuid.uuid4().hex
    for index in range(5): document(db,name,index)
    doc,chunk=document(db,name,99)
    evidence=review(db,doc)
    result=retrieve(db,name,topK=1)
    assert [r.chunkId for r in result.ragSnippets]==[chunk.id]
    assert result.ragSnippets[0].metadata["evidenceReviewId"]==evidence.id
    assert result.ragSnippets[0].metadata["documentContentHash"]==doc.content_hash
    assert result.majorContext.coreAxes is None


def test_missing_approval_returns_no_evidence(db):
    name="approval"+uuid.uuid4().hex
    document(db,name)
    result=retrieve(db,name)
    assert result.ragSnippets==[]
    assert result.retrievalMeta.fallbackUsed is True


@pytest.mark.parametrize("overrides",[
    {"content_hash":"wrong-hash"},{"dataset_version":"wrong-version"},{"domain_status":"PENDING"},
    {"implementation_status":"PENDING"},{"content_status":"REVIEWED"},{"reviewer_id":" "},{"source_reference":" "},
])
def test_incomplete_or_stale_review_is_not_accepted(db,overrides):
    name="approval"+uuid.uuid4().hex
    doc,_=document(db,name)
    review(db,doc,**overrides)
    assert retrieve(db,name).ragSnippets==[]


def test_revocation_overrides_earlier_approval(db):
    name="approval"+uuid.uuid4().hex
    doc,_=document(db,name)
    review(db,doc)
    assert retrieve(db,name).ragSnippets
    review(db,doc,decision="REVOKED")
    assert retrieve(db,name).ragSnippets==[]


def test_alias_cannot_bypass_approval(db):
    name="approval"+uuid.uuid4().hex
    alias="alias"+uuid.uuid4().hex
    doc,chunk=document(db,name)
    db.add(AiMajorAlias(alias_name=alias,normalized_alias=alias,major_key=name,major_name=name,source="test",confidence=1.0)); db.flush()
    assert retrieve(db,alias).ragSnippets==[]
    evidence=review(db,doc)
    result=retrieve(db,alias)
    assert result.ragSnippets[0].chunkId==chunk.id
    assert result.ragSnippets[0].metadata["evidenceReviewId"]==evidence.id


def test_vector_search_cannot_bypass_approval(db):
    name="approval"+uuid.uuid4().hex
    document(db,name)
    doc,chunk=document(db,name)
    review(db,doc)
    class Embeddings:
        def embed_texts(self,texts): return [[1.0]*1536 for _ in texts]
    result=MajorRagRetriever(session=db,embedding_provider=Embeddings()).retrieve(
        RetrievalRequest(majorName="unknown"+uuid.uuid4().hex,datasetVersion="approval-test-v1",approvedOnly=True,topK=1))
    assert [r.chunkId for r in result.ragSnippets]==[chunk.id]


def test_review_cannot_be_updated_in_place(db):
    name="approval"+uuid.uuid4().hex
    doc,_=document(db,name)
    row=review(db,doc)
    with pytest.raises(DBAPIError,match="append-only"):
        with db.begin_nested():
            db.execute(update(AiEvidenceReview).where(AiEvidenceReview.id==row.id).values(decision="REVOKED"))


def test_legacy_mode_still_operates_without_claiming_approval(db):
    name="approval"+uuid.uuid4().hex
    document(db,name)
    result=MajorRagRetriever(session=db).retrieve(RetrievalRequest(majorName=name,datasetVersion="approval-test-v1",enableVectorSearch=False))
    assert result.ragSnippets
    assert "evidenceReviewId" not in result.ragSnippets[0].metadata
