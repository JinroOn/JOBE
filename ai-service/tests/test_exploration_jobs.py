import uuid
import pytest
from sqlalchemy import select, update, text
from sqlalchemy.exc import DBAPIError
from app.exploration_jobs import ExplorationJobStore, ExplorationJob, JobConflict
from test_exploration_evidence_binding import db, document, review, binder, request


def setup(db):
    doc, _ = document(db); review(db, doc)
    service = binder(db)
    binding = service.prepare(request([doc.major_name])).binding
    return ExplorationJobStore(db,service),str(uuid.uuid4()),binding,doc


def test_roundtrip_stores_entire_binding_and_replay_is_idempotent(db):
    jobs,owner,binding,_=setup(db)
    job=jobs.enqueue(owner,'same',binding)
    assert jobs.enqueue(owner,'same',binding)==job
    row=db.get(ExplorationJob,job)
    assert row.binding['references_json']==binding.references_json
    assert row.binding['original_request_json']==binding.original_request_json
    assert row.binding_hash==binding.binding_hash
    claim=jobs.claim(owner,job)
    assert claim['state']=='LEASED' and claim['generationAuthorized'] is False


def test_mutation_collision_and_foreign_owner_are_rejected(db):
    jobs,owner,binding,_=setup(db);job=jobs.enqueue(owner,'same',binding)
    _,_,other,_=setup(db)
    with pytest.raises(JobConflict):jobs.enqueue(owner,'same',other)
    with pytest.raises(JobConflict):jobs.claim(str(uuid.uuid4()),job)


def test_active_lease_and_stale_retry_are_rejected(db):
    jobs,owner,binding,_=setup(db);job=jobs.enqueue(owner,'m',binding)
    first=jobs.claim(owner,job)
    with pytest.raises(JobConflict):jobs.claim(owner,job)
    jobs.retry(owner,job,first['leaseToken'])
    second=jobs.claim(owner,job)
    assert second['attempt']==2 and first['leaseToken']!=second['leaseToken']
    with pytest.raises(JobConflict):jobs.retry(owner,job,first['leaseToken'])


def test_cancel_is_idempotent_and_rejects_late_retry(db):
    jobs,owner,binding,_=setup(db);job=jobs.enqueue(owner,'m',binding)
    first=jobs.claim(owner,job)
    jobs.cancel(owner,job);jobs.cancel(owner,job)
    with pytest.raises(JobConflict):jobs.retry(owner,job,first['leaseToken'])
    with pytest.raises(JobConflict):jobs.claim(owner,job)


def test_expired_lease_reclaimed_and_old_callback_denied(db):
    jobs,owner,binding,_=setup(db);job=jobs.enqueue(owner,'m',binding)
    first=jobs.claim(owner,job)
    db.execute(text("UPDATE ai_exploration_jobs SET lease_until=statement_timestamp()-interval '1 second' WHERE id=:id"),{'id':job})
    second=jobs.claim(owner,job)
    assert second['attempt']==2
    with pytest.raises(JobConflict):jobs.retry(owner,job,first['leaseToken'])


def test_retries_are_bounded(db):
    jobs,owner,binding,_=setup(db);job=jobs.enqueue(owner,'m',binding)
    for _ in range(3):jobs.retry(owner,job,jobs.claim(owner,job)['leaseToken'])
    assert db.get(ExplorationJob,job).state=='BLOCKED'
    with pytest.raises(JobConflict):jobs.claim(owner,job)


def test_revocation_blocks_previously_saved_binding(db):
    jobs,owner,binding,doc=setup(db);job=jobs.enqueue(owner,'m',binding)
    review(db,doc,decision='REVOKED',manifest=False)
    assert jobs.claim(owner,job)['state']=='BLOCKED'
    assert db.get(ExplorationJob,job).lease_token is None


def test_database_enforces_immutable_binding(db):
    jobs,owner,binding,_=setup(db);job=jobs.enqueue(owner,'m',binding)
    with pytest.raises(DBAPIError,match='Immutable exploration job binding'):
        with db.begin_nested():db.execute(update(ExplorationJob).where(ExplorationJob.id==job).values(binding={}))


def test_erasure_removes_only_owned_job_snapshots(db):
    jobs,owner,binding,_=setup(db);job=jobs.enqueue(owner,'m',binding)
    other=str(uuid.uuid4());kept=jobs.enqueue(other,'m',binding)
    jobs.purge_owner(owner)
    assert db.scalar(select(ExplorationJob.id).where(ExplorationJob.id==job)) is None
    assert db.scalar(select(ExplorationJob.id).where(ExplorationJob.id==kept))==kept


def test_committed_job_survives_new_sessions_and_cancel_invalidates_lease(db):
    from sqlalchemy.orm import Session
    from app.rag.exploration_binding import ExplorationEvidenceBinder, EvidencePolicy
    engine=db.get_bind().engine
    owner=str(uuid.uuid4());version='TEST_JOB_'+uuid.uuid4().hex
    def store(session):return ExplorationJobStore(session,ExplorationEvidenceBinder(session,EvidencePolicy(version)))
    try:
        with Session(engine) as writer:
            doc,_=document(writer,version=version);review(writer,doc)
            service=store(writer)
            binding=service.binder.prepare(request([doc.major_name])).binding
            job=service.enqueue(owner,'durable',binding);writer.commit()
        with Session(engine) as worker:
            lease=store(worker).claim(owner,job)['leaseToken'];worker.commit()
        with Session(engine) as canceller:
            store(canceller).cancel(owner,job);canceller.commit()
        with Session(engine) as late_worker:
            with pytest.raises(JobConflict):store(late_worker).retry(owner,job,lease)
    finally:
        with Session(engine) as eraser:
            store(eraser).purge_owner(owner);eraser.commit()
