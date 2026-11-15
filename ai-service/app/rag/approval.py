"""Append-only evidence reviews. No existing document is approved implicitly."""
from datetime import datetime
from sqlalchemy import BigInteger, DateTime, ForeignKey, Index, String, Text, func, select
from sqlalchemy.orm import Mapped, mapped_column
from app.rag.models import Base, AiMajorDocument, AiMajorChunk


class AiEvidenceReview(Base):
    __tablename__ = "ai_evidence_reviews"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    document_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("ai_major_documents.id"), nullable=False)
    dataset_version: Mapped[str] = mapped_column(String(80), nullable=False)
    content_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    scope: Mapped[str] = mapped_column(String(20), nullable=False)
    decision: Mapped[str] = mapped_column(String(20), nullable=False)
    content_status: Mapped[str] = mapped_column(String(20), nullable=False)
    implementation_status: Mapped[str] = mapped_column(String(40), nullable=False)
    domain_status: Mapped[str] = mapped_column(String(20), nullable=False)
    reviewer_id: Mapped[str] = mapped_column(String(120), nullable=False)
    source_reference: Mapped[str] = mapped_column(Text, nullable=False)
    reviewed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    __table_args__ = (Index("ix_ai_evidence_review_document_scope_id", "document_id", "scope", "id"),)


def approved_review_id():
    # Latest review wins, including rejection and revocation; an old approval cannot override it.
    latest = (select(func.max(AiEvidenceReview.id))
              .where(AiEvidenceReview.document_id == AiMajorDocument.id, AiEvidenceReview.scope == "PUBLIC")
              .correlate(AiMajorDocument).scalar_subquery())
    return (select(AiEvidenceReview.id).where(
        AiEvidenceReview.id == latest,
        AiEvidenceReview.content_hash == AiMajorDocument.content_hash,
        AiEvidenceReview.dataset_version == AiMajorDocument.dataset_version,
        AiEvidenceReview.decision == "APPROVED",
        AiEvidenceReview.content_status == "ACTIVE",
        AiEvidenceReview.implementation_status == "APPROVED_FOR_IMPLEMENTATION",
        AiEvidenceReview.domain_status == "APPROVED",
        func.length(func.trim(AiEvidenceReview.reviewer_id)) > 0,
        func.length(func.trim(AiEvidenceReview.source_reference)) > 0,
    ).correlate(AiMajorDocument).scalar_subquery())


def with_approval(statement):
    review_id = approved_review_id()
    return statement.add_columns(review_id.label("evidence_review_id")).where(
        review_id.is_not(None),
        AiMajorChunk.chunk_type.in_(["description", "major_overview", "related_jobs", "service_summary"]),
    )
