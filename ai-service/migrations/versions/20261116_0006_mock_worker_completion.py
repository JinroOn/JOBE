"""Internal MOCK artifacts only; no real generation permission."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB
revision='20261116_0006'
down_revision='20261116_0005'
branch_labels=depends_on=None


def upgrade():
    op.add_column('ai_exploration_jobs',sa.Column('mock_response',JSONB(),nullable=True))
    op.add_column('ai_exploration_jobs',sa.Column('mock_response_hash',sa.String(64),nullable=True))
    op.add_column('ai_exploration_jobs',sa.Column('completed_at',sa.DateTime(timezone=True),nullable=True))
    op.drop_constraint('ck_exploration_job_state','ai_exploration_jobs',type_='check')
    op.create_check_constraint('ck_exploration_job_state','ai_exploration_jobs',"state IN ('QUEUED','LEASED','BLOCKED','CANCELLED','MOCK_COMPLETED')")
    op.create_check_constraint('ck_mock_job_response','ai_exploration_jobs',"(state='MOCK_COMPLETED' AND mock_response IS NOT NULL AND mock_response_hash IS NOT NULL AND completed_at IS NOT NULL) OR (state<>'MOCK_COMPLETED' AND mock_response IS NULL AND mock_response_hash IS NULL AND completed_at IS NULL)")
    op.execute('''CREATE FUNCTION protect_mock_job_completion() RETURNS trigger LANGUAGE plpgsql AS $$
    BEGIN
      IF NEW.state='MOCK_COMPLETED' THEN
        IF OLD.state='MOCK_COMPLETED' OR OLD.state<>'LEASED' OR OLD.lease_until<=clock_timestamp() THEN
          RAISE EXCEPTION 'Invalid MOCK completion transition';
        END IF;
      END IF;
      RETURN NEW;
    END $$''')
    op.execute('CREATE TRIGGER protect_mock_job_completion BEFORE UPDATE ON ai_exploration_jobs FOR EACH ROW EXECUTE FUNCTION protect_mock_job_completion()')


def downgrade():
    if op.get_bind().scalar(sa.text("SELECT EXISTS(SELECT 1 FROM ai_exploration_jobs WHERE state='MOCK_COMPLETED')")):
        raise RuntimeError('Cannot discard completed MOCK artifacts')
    op.execute('DROP TRIGGER protect_mock_job_completion ON ai_exploration_jobs')
    op.execute('DROP FUNCTION protect_mock_job_completion()')
    op.drop_constraint('ck_mock_job_response','ai_exploration_jobs',type_='check')
    op.drop_constraint('ck_exploration_job_state','ai_exploration_jobs',type_='check')
    op.create_check_constraint('ck_exploration_job_state','ai_exploration_jobs',"state IN ('QUEUED','LEASED','BLOCKED','CANCELLED')")
    for name in ('completed_at','mock_response_hash','mock_response'):op.drop_column('ai_exploration_jobs',name)
