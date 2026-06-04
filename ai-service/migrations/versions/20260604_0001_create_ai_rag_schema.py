"""create ai rag schema

Revision ID: 20260604_0001
Revises:
Create Date: 2026-06-04
"""

from __future__ import annotations

from alembic import op

revision = "20260604_0001"
down_revision = None
branch_labels = None
depends_on = None

EMBEDDING_DIMENSION = 1536


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")

    op.execute(
        """
        CREATE TABLE ai_major_documents (
            id BIGSERIAL PRIMARY KEY,
            major_key VARCHAR(255) NOT NULL,
            major_name VARCHAR(255) NOT NULL,
            standard_major_name VARCHAR(255),
            source_type VARCHAR(80) NOT NULL,
            source_path TEXT,
            dataset_version VARCHAR(80) NOT NULL,
            content_hash VARCHAR(128) NOT NULL,
            metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            CONSTRAINT uq_ai_major_documents_version_hash UNIQUE (dataset_version, content_hash)
        )
        """
    )

    op.execute(
        f"""
        CREATE TABLE ai_major_chunks (
            id BIGSERIAL PRIMARY KEY,
            document_id BIGINT NOT NULL REFERENCES ai_major_documents(id) ON DELETE CASCADE,
            major_key VARCHAR(255) NOT NULL,
            major_name VARCHAR(255) NOT NULL,
            chunk_index INTEGER NOT NULL,
            chunk_text TEXT NOT NULL,
            chunk_type VARCHAR(80) NOT NULL,
            source_type VARCHAR(80) NOT NULL,
            token_count INTEGER,
            content_hash VARCHAR(128) NOT NULL,
            embedding_model VARCHAR(120) NOT NULL,
            embedding vector({EMBEDDING_DIMENSION}),
            metadata_json JSONB NOT NULL DEFAULT '{{}}'::jsonb,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            CONSTRAINT uq_ai_major_chunks_document_index_hash UNIQUE (document_id, chunk_index, content_hash)
        )
        """
    )

    op.execute(
        """
        CREATE TABLE ai_major_aliases (
            id BIGSERIAL PRIMARY KEY,
            alias_name VARCHAR(255) NOT NULL,
            normalized_alias VARCHAR(255) NOT NULL,
            major_key VARCHAR(255) NOT NULL,
            major_name VARCHAR(255) NOT NULL,
            source VARCHAR(80) NOT NULL DEFAULT 'manual',
            confidence DOUBLE PRECISION NOT NULL DEFAULT 1.0,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            CONSTRAINT uq_ai_major_aliases_normalized_alias UNIQUE (normalized_alias)
        )
        """
    )

    op.execute(
        """
        CREATE TABLE ai_embedding_jobs (
            id BIGSERIAL PRIMARY KEY,
            job_id VARCHAR(80) NOT NULL UNIQUE,
            dataset_version VARCHAR(80) NOT NULL,
            embedding_model VARCHAR(120) NOT NULL,
            status VARCHAR(40) NOT NULL,
            started_at TIMESTAMPTZ NOT NULL,
            finished_at TIMESTAMPTZ,
            total_documents INTEGER NOT NULL DEFAULT 0,
            total_chunks INTEGER NOT NULL DEFAULT 0,
            failed_count INTEGER NOT NULL DEFAULT 0,
            error_message TEXT,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )

    op.execute(
        """
        CREATE TABLE ai_retrieval_logs (
            id BIGSERIAL PRIMARY KEY,
            request_id VARCHAR(120),
            query_text_hash VARCHAR(128),
            major_name VARCHAR(255),
            top_k INTEGER NOT NULL,
            score_threshold DOUBLE PRECISION,
            matched_chunk_ids BIGINT[] NOT NULL DEFAULT '{}',
            latency_ms INTEGER,
            fallback_used BOOLEAN NOT NULL DEFAULT false,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )

    op.execute("CREATE INDEX ix_ai_major_documents_major_key ON ai_major_documents (major_key)")
    op.execute("CREATE INDEX ix_ai_major_documents_major_name ON ai_major_documents (major_name)")
    op.execute("CREATE INDEX ix_ai_major_documents_dataset_version ON ai_major_documents (dataset_version)")

    op.execute("CREATE INDEX ix_ai_major_chunks_document_id ON ai_major_chunks (document_id)")
    op.execute("CREATE INDEX ix_ai_major_chunks_major_key ON ai_major_chunks (major_key)")
    op.execute("CREATE INDEX ix_ai_major_chunks_major_name ON ai_major_chunks (major_name)")
    op.execute("CREATE INDEX ix_ai_major_chunks_embedding_model ON ai_major_chunks (embedding_model)")
    op.execute(
        """
        CREATE INDEX ix_ai_major_chunks_embedding_hnsw
        ON ai_major_chunks
        USING hnsw (embedding vector_cosine_ops)
        WHERE embedding IS NOT NULL
        """
    )

    op.execute("CREATE INDEX ix_ai_major_aliases_major_key ON ai_major_aliases (major_key)")
    op.execute("CREATE INDEX ix_ai_embedding_jobs_dataset_version ON ai_embedding_jobs (dataset_version)")
    op.execute("CREATE INDEX ix_ai_embedding_jobs_status ON ai_embedding_jobs (status)")
    op.execute("CREATE INDEX ix_ai_retrieval_logs_request_id ON ai_retrieval_logs (request_id)")
    op.execute("CREATE INDEX ix_ai_retrieval_logs_major_name ON ai_retrieval_logs (major_name)")
    op.execute("CREATE INDEX ix_ai_retrieval_logs_created_at ON ai_retrieval_logs (created_at)")


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS ix_ai_retrieval_logs_created_at")
    op.execute("DROP INDEX IF EXISTS ix_ai_retrieval_logs_major_name")
    op.execute("DROP INDEX IF EXISTS ix_ai_retrieval_logs_request_id")
    op.execute("DROP INDEX IF EXISTS ix_ai_embedding_jobs_status")
    op.execute("DROP INDEX IF EXISTS ix_ai_embedding_jobs_dataset_version")
    op.execute("DROP INDEX IF EXISTS ix_ai_major_aliases_major_key")
    op.execute("DROP INDEX IF EXISTS ix_ai_major_chunks_embedding_hnsw")
    op.execute("DROP INDEX IF EXISTS ix_ai_major_chunks_embedding_model")
    op.execute("DROP INDEX IF EXISTS ix_ai_major_chunks_major_name")
    op.execute("DROP INDEX IF EXISTS ix_ai_major_chunks_major_key")
    op.execute("DROP INDEX IF EXISTS ix_ai_major_chunks_document_id")
    op.execute("DROP INDEX IF EXISTS ix_ai_major_documents_dataset_version")
    op.execute("DROP INDEX IF EXISTS ix_ai_major_documents_major_name")
    op.execute("DROP INDEX IF EXISTS ix_ai_major_documents_major_key")

    op.execute("DROP TABLE IF EXISTS ai_retrieval_logs")
    op.execute("DROP TABLE IF EXISTS ai_embedding_jobs")
    op.execute("DROP TABLE IF EXISTS ai_major_aliases")
    op.execute("DROP TABLE IF EXISTS ai_major_chunks")
    op.execute("DROP TABLE IF EXISTS ai_major_documents")

