"""Separate real model artifacts from MOCK artifacts."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB
revision='20261117_0007'
down_revision='20261116_0006'
branch_labels=depends_on=None


def upgrade():
    op.add_column('ai_exploration_jobs',sa.Column('execution_mode',sa.String(10),nullable=False,server_default='MOCK'))
    op.add_column('ai_exploration_jobs',sa.Column('model_policy',JSONB(),nullable=True))
    for name,kind in [('model_response',JSONB()),('model_response_hash',sa.String(64)),('model_metadata',JSONB()),('model_completed_at',sa.DateTime(timezone=True))]:
        op.add_column('ai_exploration_jobs',sa.Column(name,kind,nullable=True))
    op.drop_constraint('ck_exploration_job_state','ai_exploration_jobs',type_='check')
    op.create_check_constraint('ck_exploration_job_state','ai_exploration_jobs',"state IN ('QUEUED','LEASED','BLOCKED','CANCELLED','MOCK_COMPLETED','MODEL_COMPLETED')")
    op.create_check_constraint('ck_model_job_mode','ai_exploration_jobs',"execution_mode IN ('MOCK','MODEL')")
    op.create_check_constraint('ck_completed_job_mode','ai_exploration_jobs',"(state<>'MOCK_COMPLETED' OR execution_mode='MOCK') AND (state<>'MODEL_COMPLETED' OR execution_mode='MODEL')")
    op.create_check_constraint('ck_model_job_artifact','ai_exploration_jobs',"(state='MODEL_COMPLETED' AND execution_mode='MODEL' AND model_response IS NOT NULL AND model_response_hash IS NOT NULL AND model_metadata IS NOT NULL AND model_completed_at IS NOT NULL) OR (state<>'MODEL_COMPLETED' AND model_response IS NULL AND model_response_hash IS NULL AND model_metadata IS NULL AND model_completed_at IS NULL)")
    op.execute('''CREATE FUNCTION protect_model_job_completion() RETURNS trigger LANGUAGE plpgsql AS $$
    BEGIN
      IF OLD.attempt>0 AND NEW.execution_mode IS DISTINCT FROM OLD.execution_mode THEN RAISE EXCEPTION 'Immutable execution mode'; END IF;
      IF OLD.model_policy IS NOT NULL AND NEW.model_policy IS DISTINCT FROM OLD.model_policy THEN RAISE EXCEPTION 'Immutable model policy'; END IF;
      IF NEW.state='MODEL_COMPLETED' AND (OLD.state<>'LEASED' OR OLD.lease_until<=clock_timestamp()) THEN RAISE EXCEPTION 'Invalid model completion'; END IF;
      RETURN NEW;
    END $$''')
    op.execute('CREATE TRIGGER protect_model_job_completion BEFORE UPDATE ON ai_exploration_jobs FOR EACH ROW EXECUTE FUNCTION protect_model_job_completion()')


def downgrade():
    if op.get_bind().scalar(sa.text("SELECT EXISTS(SELECT 1 FROM ai_exploration_jobs WHERE execution_mode='MODEL')")):
        raise RuntimeError('Cannot discard model execution history')
    op.execute('DROP TRIGGER protect_model_job_completion ON ai_exploration_jobs')
    op.execute('DROP FUNCTION protect_model_job_completion()')
    for name in ('ck_model_job_artifact','ck_completed_job_mode','ck_model_job_mode','ck_exploration_job_state'):op.drop_constraint(name,'ai_exploration_jobs',type_='check')
    op.create_check_constraint('ck_exploration_job_state','ai_exploration_jobs',"state IN ('QUEUED','LEASED','BLOCKED','CANCELLED','MOCK_COMPLETED')")
    for name in ('execution_mode','model_policy','model_response','model_response_hash','model_metadata','model_completed_at'):op.drop_column('ai_exploration_jobs',name)
