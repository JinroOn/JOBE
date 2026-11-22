"""Scope-specific review manifests. No approval or backfill of historical reviews."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "20261116_0003"
down_revision = "20261115_0002"
branch_labels = None
depends_on = None


def upgrade():
    op.drop_constraint("ck_evidence_review_scope", "ai_evidence_reviews", type_="check")
    op.create_check_constraint("ck_evidence_review_scope", "ai_evidence_reviews", "scope IN ('DEV','PILOT','PUBLIC')")
    op.add_column("ai_evidence_reviews", sa.Column("binding_manifest", postgresql.JSONB(), nullable=True))
    op.create_check_constraint("ck_evidence_binding_manifest", "ai_evidence_reviews",
                               "binding_manifest IS NULL OR jsonb_typeof(binding_manifest) = 'object'")


def downgrade():
    # Never delete immutable reviews or silently discard their evidence to permit a downgrade.
    present = op.get_bind().scalar(sa.text("SELECT EXISTS(SELECT 1 FROM ai_evidence_reviews "
                                          "WHERE scope <> 'PUBLIC' OR binding_manifest IS NOT NULL)"))
    if present:
        raise RuntimeError("Cannot discard scoped review evidence; preserve review history before downgrading")
    op.drop_constraint("ck_evidence_binding_manifest", "ai_evidence_reviews", type_="check")
    op.drop_column("ai_evidence_reviews", "binding_manifest")
    op.drop_constraint("ck_evidence_review_scope", "ai_evidence_reviews", type_="check")
    op.create_check_constraint("ck_evidence_review_scope", "ai_evidence_reviews", "scope = 'PUBLIC'")
