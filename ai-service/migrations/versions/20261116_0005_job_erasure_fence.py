"""Minimal opaque erasure fence prevents delayed requests recreating purged jobs."""
from alembic import op
import sqlalchemy as sa
revision='20261116_0005'
down_revision='20261116_0004'
branch_labels=depends_on=None


def upgrade():
    op.create_table('ai_exploration_erased_owners',sa.Column('owner_key',sa.String(36),primary_key=True))


def downgrade():
    if op.get_bind().scalar(sa.text('SELECT EXISTS(SELECT 1 FROM ai_exploration_erased_owners)')):
        raise RuntimeError('Cannot discard erasure fences')
    op.drop_table('ai_exploration_erased_owners')
