import pytest
from app.exploration_worker import ModelExplorationWorker,MockExplorationWorker,mock_response
from app.exploration_model import ExplanationOutputIssue, ModelFault, PROMPT_VERSION
from app.exploration_jobs import ExplorationJob,JobConflict
from test_exploration_worker import fixture


class Consumer:
    metadata={'provider':'openai','model':'TEST_MODEL','promptVersion':PROMPT_VERSION}
    def generate(self,request,facts):
        value=mock_response(request,facts);value['result']['summary']='TEST model output';return value


def test_model_artifact_is_separate_and_mock_worker_cannot_reinterpret_it(fixture):
    owner,sessions,store,create,policy=fixture;job,_=create()
    assert ModelExplorationWorker(sessions,policy,Consumer()).run_one(owner,job)=='MODEL_COMPLETED'
    with sessions() as db:
        row=db.get(ExplorationJob,job);assert row.execution_mode=='MODEL' and row.mock_response is None
        assert row.model_response is not None and row.model_metadata==Consumer.metadata
    with pytest.raises(JobConflict):MockExplorationWorker(sessions,policy).run_one(owner,job)


def test_transient_model_failure_releases_lease_and_pins_configuration(fixture):
    owner,sessions,store,create,policy=fixture;job,_=create()
    class Broken(Consumer):
        def generate(self,*a):raise ModelFault('MODEL_UNAVAILABLE',True)
    with pytest.raises(ModelFault):ModelExplorationWorker(sessions,policy,Broken()).run_one(owner,job)
    with sessions() as db:
        row=db.get(ExplorationJob,job);assert row.state=='QUEUED' and row.execution_mode=='MODEL' and row.lease_token is None
    class Changed(Consumer):metadata={**Consumer.metadata,'model':'OTHER'}
    with pytest.raises(ModelFault):ModelExplorationWorker(sessions,policy,Changed()).run_one(owner,job)
    assert ModelExplorationWorker(sessions,policy,Consumer()).run_one(owner,job)=='MODEL_COMPLETED'


def test_cancel_during_model_call_discards_output(fixture):
    owner,sessions,store,create,policy=fixture;job,_=create()
    class Cancelled(Consumer):
        def generate(self,*args):
            with sessions() as db:store(db).cancel(owner,job);db.commit()
            return super().generate(*args)
    with pytest.raises(JobConflict):ModelExplorationWorker(sessions,policy,Cancelled()).run_one(owner,job)
    with sessions() as db:assert db.get(ExplorationJob,job).model_response is None


def test_permanent_model_failure_is_blocked_without_artifact(fixture):
    owner,sessions,store,create,policy=fixture;job,_=create()
    class Broken(Consumer):
        def generate(self,*a):raise ModelFault('MODEL_AUTH_FAILED')
    with pytest.raises(ModelFault):ModelExplorationWorker(sessions,policy,Broken()).run_one(owner,job)
    with sessions() as db:
        row=db.get(ExplorationJob,job);assert row.state=='BLOCKED' and row.model_response is None and row.lease_token is None


def test_output_diagnostic_persists_only_allowlisted_reason(fixture, caplog):
    owner, sessions, _, create, policy = fixture
    job, _ = create()

    class InvalidOutput(Consumer):
        def generate(self, *args):
            raise ModelFault('MODEL_OUTPUT_INVALID', output_issue=ExplanationOutputIssue.EXCERPT)

    with pytest.raises(ModelFault):
        ModelExplorationWorker(sessions, policy, InvalidOutput()).run_one(owner, job)
    with sessions() as db:
        row = db.get(ExplorationJob, job)
        assert row.state == 'BLOCKED'
        assert row.reason == ExplanationOutputIssue.EXCERPT
        assert row.model_response is None
    assert caplog.messages == [f'Explanation output rejected jobId={job} reason={ExplanationOutputIssue.EXCERPT}']
