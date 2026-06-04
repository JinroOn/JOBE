from __future__ import annotations

from datetime import datetime

from pgvector.sqlalchemy import Vector
from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    func,
)
from sqlalchemy.dialects.postgresql import ARRAY, JSONB
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


EMBEDDING_DIMENSION = 1536


class Base(DeclarativeBase):
    pass


class TimestampMixin:
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
    )


class AiMajorDocument(Base, TimestampMixin):
    __tablename__ = "ai_major_documents"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    major_key: Mapped[str] = mapped_column(String(255), nullable=False)
    major_name: Mapped[str] = mapped_column(String(255), nullable=False)
    standard_major_name: Mapped[str | None] = mapped_column(String(255))
    source_type: Mapped[str] = mapped_column(String(80), nullable=False)
    source_path: Mapped[str | None] = mapped_column(Text)
    dataset_version: Mapped[str] = mapped_column(String(80), nullable=False)
    content_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    metadata_json: Mapped[dict] = mapped_column(JSONB, nullable=False, server_default="{}")

    chunks: Mapped[list[AiMajorChunk]] = relationship(
        back_populates="document",
        cascade="all, delete-orphan",
    )

    __table_args__ = (
        Index("ix_ai_major_documents_major_key", "major_key"),
        Index("ix_ai_major_documents_major_name", "major_name"),
        Index("ix_ai_major_documents_dataset_version", "dataset_version"),
        Index("uq_ai_major_documents_version_hash", "dataset_version", "content_hash", unique=True),
    )


class AiMajorChunk(Base, TimestampMixin):
    __tablename__ = "ai_major_chunks"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    document_id: Mapped[int] = mapped_column(
        BigInteger,
        ForeignKey("ai_major_documents.id", ondelete="CASCADE"),
        nullable=False,
    )
    major_key: Mapped[str] = mapped_column(String(255), nullable=False)
    major_name: Mapped[str] = mapped_column(String(255), nullable=False)
    chunk_index: Mapped[int] = mapped_column(Integer, nullable=False)
    chunk_text: Mapped[str] = mapped_column(Text, nullable=False)
    chunk_type: Mapped[str] = mapped_column(String(80), nullable=False)
    source_type: Mapped[str] = mapped_column(String(80), nullable=False)
    token_count: Mapped[int | None] = mapped_column(Integer)
    content_hash: Mapped[str] = mapped_column(String(128), nullable=False)
    embedding_model: Mapped[str] = mapped_column(String(120), nullable=False)
    embedding: Mapped[list[float] | None] = mapped_column(Vector(EMBEDDING_DIMENSION))
    metadata_json: Mapped[dict] = mapped_column(JSONB, nullable=False, server_default="{}")

    document: Mapped[AiMajorDocument] = relationship(back_populates="chunks")

    __table_args__ = (
        Index("ix_ai_major_chunks_document_id", "document_id"),
        Index("ix_ai_major_chunks_major_key", "major_key"),
        Index("ix_ai_major_chunks_major_name", "major_name"),
        Index("ix_ai_major_chunks_embedding_model", "embedding_model"),
        Index("uq_ai_major_chunks_document_index_hash", "document_id", "chunk_index", "content_hash", unique=True),
    )


class AiMajorAlias(Base, TimestampMixin):
    __tablename__ = "ai_major_aliases"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    alias_name: Mapped[str] = mapped_column(String(255), nullable=False)
    normalized_alias: Mapped[str] = mapped_column(String(255), nullable=False)
    major_key: Mapped[str] = mapped_column(String(255), nullable=False)
    major_name: Mapped[str] = mapped_column(String(255), nullable=False)
    source: Mapped[str] = mapped_column(String(80), nullable=False, server_default="manual")
    confidence: Mapped[float] = mapped_column(Float, nullable=False, server_default="1.0")

    __table_args__ = (
        Index("ix_ai_major_aliases_normalized_alias", "normalized_alias", unique=True),
        Index("ix_ai_major_aliases_major_key", "major_key"),
    )


class AiEmbeddingJob(Base):
    __tablename__ = "ai_embedding_jobs"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    job_id: Mapped[str] = mapped_column(String(80), nullable=False, unique=True)
    dataset_version: Mapped[str] = mapped_column(String(80), nullable=False)
    embedding_model: Mapped[str] = mapped_column(String(120), nullable=False)
    status: Mapped[str] = mapped_column(String(40), nullable=False)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    total_documents: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    total_chunks: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    failed_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default="0")
    error_message: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )

    __table_args__ = (
        Index("ix_ai_embedding_jobs_dataset_version", "dataset_version"),
        Index("ix_ai_embedding_jobs_status", "status"),
    )


class AiRetrievalLog(Base):
    __tablename__ = "ai_retrieval_logs"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    request_id: Mapped[str | None] = mapped_column(String(120))
    query_text_hash: Mapped[str | None] = mapped_column(String(128))
    major_name: Mapped[str | None] = mapped_column(String(255))
    top_k: Mapped[int] = mapped_column(Integer, nullable=False)
    score_threshold: Mapped[float | None] = mapped_column(Float)
    matched_chunk_ids: Mapped[list[int]] = mapped_column(ARRAY(BigInteger), nullable=False, server_default="{}")
    latency_ms: Mapped[int | None] = mapped_column(Integer)
    fallback_used: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default="false")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )

    __table_args__ = (
        Index("ix_ai_retrieval_logs_request_id", "request_id"),
        Index("ix_ai_retrieval_logs_major_name", "major_name"),
        Index("ix_ai_retrieval_logs_created_at", "created_at"),
    )

