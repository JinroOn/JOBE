from contextlib import contextmanager
import os
import uuid
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import Session
from app.main import app,rate_limiter
from app import exploration_job_transport as wire
from app.exploration_contract import input_hash
from test_exploration_evidence_binding import document,review,request


@pytest.fixture
def scenario(monkeypatch):
    url=os.getenv('SECTION32_TEST_DATABASE_URL')
    if not url:pytest.skip('Dedicated PostgreSQL required')
    engine=create_engine(url)
    version='TEST_WIRE_'+uuid.uuid4().hex
    with Session(engine) as db:
        doc,_=document(db,version=version);review(db,doc)
        value=request([doc.major_name]);db.commit()
    @contextmanager
    def sessions():
        with Session(engine) as db:yield db
    monkeypatch.setattr(wire,'evidence_session',sessions)
    monkeypatch.setenv('EXPLORATION_EVIDENCE_ENABLED','true')
    monkeypatch.setenv('EXPLORATION_EVIDENCE_DATASET_VERSION',version)
    monkeypatch.setenv('INTERNAL_SERVICE_TOKEN','wire-test')
    rate_limiter.window.clear()
    payload={'ownerKey':str(uuid.uuid4()),'mutationKey':'stable','request':value}
    with TestClient(app,raise_server_exceptions=False) as client:
        def call(op,body=payload,auth=True):
            return client.post('/v1/exploration/jobs/'+op,json=body,headers={'Authorization':'Bearer wire-test'} if auth else {})
        yield payload,call
        call('purge',{'ownerKey':payload['ownerKey']})
    rate_limiter.window.clear();engine.dispose()


def test_enqueue_replay_read_cancel_across_real_commits(scenario):
    body,call=scenario
    first=call('enqueue');assert first.status_code==200
    assert first.json()['status']=='QUEUED' and first.json()['generationAuthorized'] is False
    assert call('enqueue').json()==first.json()
    assert call('read').json()==first.json()
    assert call('cancel').json()['status']=='CANCELLED'
    assert call('enqueue').json()['status']=='CANCELLED'
    assert call('cancel').json()['status']=='CANCELLED'


def test_erasure_rejects_delayed_enqueue_and_is_idempotent(scenario):
    body,call=scenario;call('enqueue')
    purge={'ownerKey':body['ownerKey']}
    assert call('purge',purge).json()['status']=='ERASED'
    assert call('purge',purge).status_code==200
    for op in ['enqueue','read','cancel']:assert call(op).status_code==409


def test_purge_before_enqueue_also_blocks_inflight_creation(scenario):
    body,call=scenario
    assert call('purge',{'ownerKey':body['ownerKey']}).status_code==200
    assert call('enqueue').status_code==409


def test_concurrent_enqueue_and_purge_never_leave_a_recreated_job(scenario):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Barrier
    body,call=scenario;start=Barrier(2)
    def create():start.wait();return call('enqueue').status_code
    def erase():start.wait();return call('purge',{'ownerKey':body['ownerKey']}).status_code
    with ThreadPoolExecutor(max_workers=2) as pool:
        creation=pool.submit(create);erasure=pool.submit(erase)
        assert creation.result(timeout=10) in {200,409}
        assert erasure.result(timeout=10)==200
    assert call('read').status_code==409
    assert call('enqueue').status_code==409


def test_purge_works_with_preparation_disabled_and_invalid_policy(scenario,monkeypatch):
    body,call=scenario;call('enqueue')
    monkeypatch.setenv('EXPLORATION_EVIDENCE_ENABLED','false')
    monkeypatch.setenv('EXPLORATION_EVIDENCE_DATASET_VERSION','')
    assert call('read').status_code==503
    assert call('purge',{'ownerKey':body['ownerKey']}).status_code==200


def test_foreign_owner_cannot_read_or_cancel(scenario):
    body,call=scenario;call('enqueue')
    foreign={**body,'ownerKey':str(uuid.uuid4())}
    assert call('read',foreign).status_code==409
    assert call('cancel',foreign).status_code==409
    assert call('read').json()['status']=='QUEUED'


def test_changed_body_conflicts_without_mutating_saved_job(scenario):
    body,call=scenario;call('enqueue')
    from copy import deepcopy
    other=deepcopy(body);other['request']['requestId']='different'
    assert call('enqueue',other).status_code==409
    assert call('read').json()['status']=='QUEUED'


@pytest.mark.parametrize('operation',['enqueue','read','cancel','purge'])
def test_every_operation_requires_auth(scenario,operation):
    body,call=scenario
    assert call(operation,auth=False).status_code==401


def test_unknown_fields_and_invalid_request_rejected(scenario):
    body,call=scenario
    assert call('enqueue',{**body,'extra':'PRIVATE'}).status_code==422
    assert call('enqueue',{**body,'ownerKey':'not-owner'}).status_code==409
    assert call('anything').status_code==409


def test_mock_execution_is_disabled_by_default(scenario):
    body,call=scenario;call('enqueue')
    assert call('mock-execute').status_code==503


def test_mock_execution_returns_bound_validated_artifact_and_replays(scenario,monkeypatch):
    from app.exploration_contract import validate_response
    body,call=scenario;call('enqueue');monkeypatch.setenv('EXPLORATION_MOCK_EXECUTION_ENABLED','true')
    response=call('mock-execute');assert response.status_code==200
    result=response.json()
    assert set(result)=={'transportVersion','mode','generationAuthorized','mutationKey','originalInputHash','sourceContextHash','jobId','bindingHash','boundRequest','response'}
    assert result['mode']=='MOCK' and result['generationAuthorized'] is False
    assert result['originalInputHash']==body['request']['inputHash']
    validate_response(result['boundRequest'],result['response'])
    assert call('mock-execute').json()==result


def test_cancel_or_erasure_prevents_mock_execution(scenario,monkeypatch):
    body,call=scenario;call('enqueue');monkeypatch.setenv('EXPLORATION_MOCK_EXECUTION_ENABLED','true')
    call('cancel');assert call('mock-execute').status_code==409
    call('purge',{'ownerKey':body['ownerKey']});assert call('mock-execute').status_code==409


def test_mock_endpoint_rejects_foreign_owner_and_requires_auth(scenario,monkeypatch):
    body,call=scenario;call('enqueue');monkeypatch.setenv('EXPLORATION_MOCK_EXECUTION_ENABLED','true')
    assert call('mock-execute',auth=False).status_code==401
    assert call('mock-execute',{**body,'ownerKey':str(uuid.uuid4())}).status_code==409


def test_mock_plan_uses_the_selected_target(scenario,monkeypatch):
    from copy import deepcopy
    body,call=scenario;body=deepcopy(body);body['request']['purpose']='PLAN'
    body['request']['input']['targetName']=body['request']['input']['candidateNames'][0]
    body['request']['inputHash']=input_hash(body['request'])
    monkeypatch.setenv('EXPLORATION_MOCK_EXECUTION_ENABLED','true')
    assert call('enqueue',body).status_code==200
    result=call('mock-execute',body);assert result.status_code==200
    assert result.json()['response']['result']['targetName']==body['request']['input']['targetName']
