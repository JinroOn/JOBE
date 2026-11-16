"""Durable internal preparation jobs; no generation or automatic approval."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB

revision = '20261116_0004'
down_revision = '20261116_0003'
branch_labels = depends_on = None


def upgrade():
    op.create_table('ai_exploration_jobs',
        sa.Column('id', sa.BigInteger(), primary_key=True),
        sa.Column('owner_key', sa.String(36), nullable=False),
        sa.Column('mutation_key', sa.String(80), nullable=False),
        sa.Column('binding_hash', sa.String(64), nullable=False),
        sa.Column('binding', JSONB(), nullable=False),
        sa.Column('state', sa.String(20), nullable=False),
        sa.Column('attempt', sa.Integer(), nullable=False),
        sa.Column('lease_token', sa.String(36)),
        sa.Column('lease_until', sa.DateTime(timezone=True)),
        sa.Column('reason', sa.Text()),
        sa.UniqueConstraint('owner_key','mutation_key', name='uq_exploration_job_mutation'),
        sa.CheckConstraint("state IN ('QUEUED','LEASED','BLOCKED','CANCELLED')", name='ck_exploration_job_state'),
        sa.CheckConstraint('attempt BETWEEN 0 AND 3', name='ck_exploration_job_attempt'),
        sa.CheckConstraint("(state='LEASED' AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR (state<>'LEASED' AND lease_token IS NULL AND lease_until IS NULL)", name='ck_exploration_job_lease'))
    op.execute('''CREATE FUNCTION protect_exploration_job_binding() RETURNS trigger LANGUAGE plpgsql AS $$
    BEGIN
      IF NEW.owner_key IS DISTINCT FROM OLD.owner_key OR NEW.mutation_key IS DISTINCT FROM OLD.mutation_key
        OR NEW.binding_hash IS DISTINCT FROM OLD.binding_hash OR NEW.binding IS DISTINCT FROM OLD.binding THEN
        RAISE EXCEPTION 'Immutable exploration job binding';
      END IF;
      RETURN NEW;
    END $$''')
    op.execute('CREATE TRIGGER immutable_exploration_job_binding BEFORE UPDATE ON ai_exploration_jobs FOR EACH ROW EXECUTE FUNCTION protect_exploration_job_binding()')


def downgrade():
    if op.get_bind().scalar(sa.text('SELECT EXISTS(SELECT 1 FROM ai_exploration_jobs)')):
        raise RuntimeError('Cannot discard saved exploration jobs')
    op.drop_table('ai_exploration_jobs')
    op.execute('DROP FUNCTION protect_exploration_job_binding()')
