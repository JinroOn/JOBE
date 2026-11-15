"""Append-only per-document public evidence review history; no automatic approval/backfill."""
from alembic import op
import sqlalchemy as sa

revision = "20261115_0002"
down_revision = "20260604_0001"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table("ai_evidence_reviews",
        sa.Column("id",sa.BigInteger(),primary_key=True,autoincrement=True),
        sa.Column("document_id",sa.BigInteger(),sa.ForeignKey("ai_major_documents.id"),nullable=False),
        sa.Column("dataset_version",sa.String(80),nullable=False),
        sa.Column("content_hash",sa.String(128),nullable=False),
        sa.Column("scope",sa.String(20),nullable=False),
        sa.Column("decision",sa.String(20),nullable=False),
        sa.Column("content_status",sa.String(20),nullable=False),
        sa.Column("implementation_status",sa.String(40),nullable=False),
        sa.Column("domain_status",sa.String(20),nullable=False),
        sa.Column("reviewer_id",sa.String(120),nullable=False),
        sa.Column("source_reference",sa.Text(),nullable=False),
        sa.Column("reviewed_at",sa.DateTime(timezone=True),nullable=False,server_default=sa.func.now()),
        sa.CheckConstraint("scope = 'PUBLIC'",name="ck_evidence_review_scope"),
        sa.CheckConstraint("decision IN ('APPROVED','REJECTED','REVOKED')",name="ck_evidence_review_decision"))
    op.create_index("ix_ai_evidence_review_document_scope_id","ai_evidence_reviews",["document_id","scope","id"])
    op.execute("""CREATE FUNCTION prevent_evidence_review_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
    BEGIN RAISE EXCEPTION 'Evidence reviews are append-only; append a new review or revocation'; END $$""")
    op.execute("CREATE TRIGGER evidence_review_append_only BEFORE UPDATE OR DELETE ON ai_evidence_reviews FOR EACH ROW EXECUTE FUNCTION prevent_evidence_review_mutation()")


def downgrade():
    op.drop_table("ai_evidence_reviews")
    op.execute("DROP FUNCTION prevent_evidence_review_mutation()")
