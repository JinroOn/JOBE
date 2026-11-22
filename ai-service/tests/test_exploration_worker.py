import os
import uuid
from copy import deepcopy
import pytest
from sqlalchemy import create_engine,select,text,update
from sqlalchemy.orm import Session
from sqlalchemy.exc import DBAPIError
from app import exploration_worker as worker
from app.exploration_jobs import ExplorationJob,ExplorationJobStore,JobConflict
from app.rag.exploration_binding import EvidencePolicy,ExplorationEvidenceBinder,_hash
from test_exploration_evidence_binding import document,review,request


@pytest.fixture
def fixture():
    url=os.getenv('SECTION32_TEST_DATABASE_URL')
    if not url:pytest.skip('Disposable PostgreSQL required')
    engine=create_engine(url);owner=str(uuid.uuid4());policy=EvidencePolicy('TEST_WORKER_'+uuid.uuid4().hex)
    def sessions():return Session(engine)
    def store(db):return ExplorationJobStore(db,ExplorationEvidenceBinder(db,policy))
    def create(purpose='CONSULTATION'):
        with sessions() as db:
            doc,_=document(db,version=policy.dataset_version);review(db,doc)
            binding=store(db).binder.prepare(request([doc.major_name],purpose=purpose,target=doc.major_name if purpose=='PLAN' else None)).binding
            job=store(db).enqueue(owner,uuid.uuid4().hex,binding);docid=doc.id;db.commit()
        return job,docid
    yield owner,sessions,store,create,policy
    with sessions() as db:store(db).purge_owner(owner);db.commit()
    engine.dispose()


@pytest.mark.parametrize('purpose',['CONSULTATION','PLAN'])
def test_worker_persists_valid_mock_artifact_without_generation(fixture,purpose):
    owner,sessions,store,create,policy=fixture;job,_=create(purpose)
    assert worker.MockExplorationWorker(sessions,policy).run_one(owner,job)=='MOCK_COMPLETED'
    with sessions() as db:
        row=db.get(ExplorationJob,job)
        assert row.mock_response_hash==_hash(row.mock_response)
        assert row.reason=='MOCK_ONLY_NOT_GENERATED' and row.lease_token is None
        assert row.completed_at is not None
        assert '[MOCK]' in str(row.mock_response)
    with pytest.raises(JobConflict):worker.MockExplorationWorker(sessions,policy).run_one(owner,job)


def test_cancel_during_computation_discards_output(fixture,monkeypatch):
    owner,sessions,store,create,policy=fixture;job,_=create();original=worker.mock_response
    def cancelled(request,facts):
        with sessions() as db:store(db).cancel(owner,job);db.commit()
        return original(request,facts)
    monkeypatch.setattr(worker,'mock_response',cancelled)
    with pytest.raises(JobConflict):worker.MockExplorationWorker(sessions,policy).run_one(owner,job)
    with sessions() as db:
        row=db.get(ExplorationJob,job);assert row.state=='CANCELLED' and row.mock_response is None


def test_purge_during_computation_discards_output(fixture,monkeypatch):
    owner,sessions,store,create,policy=fixture;job,_=create();original=worker.mock_response
    def erased(request,facts):
        with sessions() as db:store(db).purge_owner(owner);db.commit()
        return original(request,facts)
    monkeypatch.setattr(worker,'mock_response',erased)
    with pytest.raises(JobConflict):worker.MockExplorationWorker(sessions,policy).run_one(owner,job)
    with sessions() as db:assert db.get(ExplorationJob,job) is None


def test_revocation_during_computation_blocks_completion(fixture,monkeypatch):
    owner,sessions,store,create,policy=fixture;job,docid=create();original=worker.mock_response
    def revoked(request,facts):
        from app.rag.models import AiMajorDocument
        with sessions() as db:review(db,db.get(AiMajorDocument,docid),decision='REVOKED',manifest=False);db.commit()
        return original(request,facts)
    monkeypatch.setattr(worker,'mock_response',revoked)
    assert worker.MockExplorationWorker(sessions,policy).run_one(owner,job)=='BLOCKED'
    with sessions() as db:assert db.get(ExplorationJob,job).mock_response is None


def test_expiry_during_computation_rejects_late_output(fixture,monkeypatch):
    owner,sessions,store,create,policy=fixture;job,_=create();original=worker.mock_response
    def expired(request,facts):
        with sessions() as db:
            db.execute(text("UPDATE ai_exploration_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=:id"),{'id':job});db.commit()
        return original(request,facts)
    monkeypatch.setattr(worker,'mock_response',expired)
    with pytest.raises(JobConflict):worker.MockExplorationWorker(sessions,policy).run_one(owner,job)
    with sessions() as db:assert db.get(ExplorationJob,job).mock_response is None


@pytest.mark.parametrize('change',['hash','reference','extra'])
def test_invalid_output_is_not_saved(fixture,monkeypatch,change):
    owner,sessions,store,create,policy=fixture;job,_=create();original=worker.mock_response
    def invalid(request,facts):
        value=original(request,facts)
        if change=='hash':value['inputHash']='0'*64
        elif change=='reference':value['result']['referenceKeys']=['unissued']
        else:value['private']='PRIVATE'
        return value
    monkeypatch.setattr(worker,'mock_response',invalid)
    assert worker.MockExplorationWorker(sessions,policy).run_one(owner,job)=='BLOCKED'
    with sessions() as db:
        row=db.get(ExplorationJob,job);assert row.mock_response is None and row.mock_response_hash is None


def test_final_commit_failure_rolls_back_artifact_and_completion(fixture):
    owner,sessions,store,create,policy=fixture;job,_=create();count=[0]
    class FailingSession(Session):
        def commit(self):
            count[0]+=1
            if count[0]==2:raise RuntimeError('SYNTHETIC_COMMIT_FAILURE')
            return super().commit()
    with sessions() as probe:engine=probe.get_bind()
    with pytest.raises(RuntimeError,match='SYNTHETIC_COMMIT_FAILURE'):
        worker.MockExplorationWorker(lambda:FailingSession(engine),policy).run_one(owner,job)
    with sessions() as db:
        row=db.get(ExplorationJob,job);assert row.state=='LEASED' and row.mock_response is None and row.completed_at is None


def test_completed_artifact_cannot_be_overwritten_and_cancel_erases_it(fixture):
    owner,sessions,store,create,policy=fixture;job,_=create()
    worker.MockExplorationWorker(sessions,policy).run_one(owner,job)
    with sessions() as db:
        with pytest.raises(DBAPIError,match='Invalid MOCK completion'):
            with db.begin_nested():db.execute(update(ExplorationJob).where(ExplorationJob.id==job).values(mock_response={}))
        store(db).cancel(owner,job);db.commit()
    with sessions() as db:assert db.get(ExplorationJob,job).mock_response is None


def test_computation_runs_without_an_open_claim_transaction(fixture,monkeypatch):
    owner,sessions,store,create,policy=fixture;job,_=create();original=worker.mock_response
    def check(request,facts):
        with sessions() as db:
            db.execute(text("SET LOCAL lock_timeout='300ms'"))
            row=db.scalar(select(ExplorationJob).where(ExplorationJob.id==job).with_for_update(nowait=True))
            assert row.state=='LEASED'
        return original(request,facts)
    monkeypatch.setattr(worker,'mock_response',check)
    assert worker.MockExplorationWorker(sessions,policy).run_one(owner,job)=='MOCK_COMPLETED'


def test_lease_expiring_during_final_evidence_validation_is_rejected(fixture,monkeypatch):
    from app.rag.exploration_binding import EvidenceBinding
    owner,sessions,store,create,policy=fixture;job,_=create()
    with sessions() as db:
        service=store(db);lease=service.claim(owner,job)['leaseToken']
        row=db.get(ExplorationJob,job);binding=EvidenceBinding(**row.binding)
        db.execute(text("UPDATE ai_exploration_jobs SET lease_until=clock_timestamp()+interval '100 milliseconds' WHERE id=:id"),{'id':job});db.refresh(row)
        original=service.binder.revalidate
        def slow(value):original(value);db.execute(text('SELECT pg_sleep(0.2)'));return True
        monkeypatch.setattr(service.binder,'revalidate',slow)
        response=worker.mock_response(binding.request,binding._model_evidence())
        with pytest.raises(JobConflict):service.complete_mock(owner,job,lease,response)
        assert row.mock_response is None


def test_reclaimed_lease_rejects_old_completion_token(fixture):
    from app.rag.exploration_binding import EvidenceBinding
    owner,sessions,store,create,policy=fixture;job,_=create()
    with sessions() as db:
        service=store(db);old=service.claim(owner,job)['leaseToken']
        db.execute(text("UPDATE ai_exploration_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE id=:id"),{'id':job})
        current=service.claim(owner,job)['leaseToken'];binding=EvidenceBinding(**db.get(ExplorationJob,job).binding)
        response=worker.mock_response(binding.request,binding._model_evidence())
        with pytest.raises(JobConflict):service.complete_mock(owner,job,old,response)
        service.complete_mock(owner,job,current,response);db.commit()
