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


def test_active_remote_lease_is_a_retryable_busy_response(scenario,monkeypatch):
    from app.exploration_jobs import ExplorationJobStore
    from app.rag.exploration_binding import EvidencePolicy,ExplorationEvidenceBinder
    body,call=scenario;job=call('enqueue').json()['jobId']
    monkeypatch.setenv('EXPLORATION_MOCK_EXECUTION_ENABLED','true')
    with wire.evidence_session() as db:
        store=ExplorationJobStore(db,ExplorationEvidenceBinder(db,EvidencePolicy(os.environ['EXPLORATION_EVIDENCE_DATASET_VERSION'])))
        store.claim(body['ownerKey'],job);db.commit()
    response=call('mock-execute');assert response.status_code==409
    assert response.json()['code']=='EXPLORATION_JOB_BUSY' and response.json()['retryable'] is True


def test_model_endpoint_requires_explicit_activation(scenario):
    body,call=scenario;call('enqueue')
    response=call('model-execute');assert response.status_code==503
    assert response.json()['code']=='MODEL_CONFIGURATION_INVALID'


@pytest.fixture
def injected_model(monkeypatch):
    from app.exploration_worker import mock_response
    class Consumer:
        metadata={'provider':'openai','model':'TEST_MODEL','promptVersion':'EXPLORATION_MODEL_V1_0'}
        calls=0
        def generate(self,request,facts):
            self.calls+=1
            return mock_response(request,facts)
    consumer=Consumer()
    monkeypatch.setenv('EXPLORATION_MODEL_EXECUTION_ENABLED','true');monkeypatch.setenv('MOCK_MODE','false')
    monkeypatch.setattr(wire,'ExplorationModel',lambda:consumer)
    return consumer


def test_model_transport_is_explicit_and_pins_artifact_provenance(scenario,injected_model):
    body,call=scenario;call('enqueue');response=call('model-execute')
    assert response.status_code==200
    result=response.json();assert result['mode']=='MODEL' and result['generationAuthorized'] is True
    assert result['providerMetadata']==injected_model.metadata
    assert call('model-execute').json()==result and injected_model.calls==1


def test_model_and_mock_cannot_reinterpret_the_same_job(scenario,injected_model,monkeypatch):
    body,call=scenario;call('enqueue');assert call('model-execute').status_code==200
    monkeypatch.setenv('EXPLORATION_MOCK_EXECUTION_ENABLED','true')
    assert call('mock-execute').status_code==409


def test_model_transport_requires_auth_and_rejects_foreign_owner(scenario,injected_model):
    body,call=scenario;call('enqueue')
    assert call('model-execute',auth=False).status_code==401
    assert call('model-execute',{**body,'ownerKey':str(uuid.uuid4())}).status_code==409
    assert injected_model.calls==0


def test_model_transport_cancel_removes_artifact_and_rejects_reexecution(scenario,injected_model):
    body,call=scenario;call('enqueue');assert call('model-execute').status_code==200
    assert call('cancel').status_code==200
    assert call('model-execute').status_code==409 and injected_model.calls==1


def conversation_request(body):
    body['request']['contractVersion']='EXPLORATION_AI_CONTRACT_V1_1'
    body['request']['input']['conversation']={
        'history':[{'role':'user','content':'SYNTHETIC earlier question'},
                   {'role':'assistant','content':'SYNTHETIC earlier answer, not approved evidence'}],
        'question':'SYNTHETIC current question',
    }
    body['request']['inputHash']=input_hash(body['request'])


def test_conversation_model_transport_preserves_question_history_and_idempotent_replay(scenario,injected_model):
    body,call=scenario;conversation_request(body)
    assert call('enqueue').status_code==200
    first=call('model-execute');assert first.status_code==200
    result=first.json()
    assert result['boundRequest']['input']['conversation']==body['request']['input']['conversation']
    assert result['response']['contractVersion']=='EXPLORATION_AI_CONTRACT_V1_1'
    assert result['originalInputHash']==body['request']['inputHash']
    assert call('model-execute').json()==result and injected_model.calls==1


def test_conversation_change_cannot_reuse_original_hash_or_completed_mutation(scenario,injected_model):
    body,call=scenario;conversation_request(body);assert call('enqueue').status_code==200
    assert call('model-execute').status_code==200
    body['request']['input']['conversation']['question']='Changed question'
    assert call('model-execute').status_code==422
    body['request']['inputHash']=input_hash(body['request'])
    assert call('model-execute').status_code==409 and injected_model.calls==1


def test_conversation_approval_revoked_during_model_call_leaves_no_artifact(scenario,injected_model):
    from sqlalchemy import select
    from app.exploration_jobs import ExplorationJob
    from app.rag.models import AiMajorDocument
    body,call=scenario;conversation_request(body);job=call('enqueue').json()['jobId']
    generate=injected_model.generate
    def revoked(request,facts):
        value=generate(request,facts)
        with wire.evidence_session() as db:
            doc=db.scalars(select(AiMajorDocument).where(AiMajorDocument.dataset_version==os.environ['EXPLORATION_EVIDENCE_DATASET_VERSION'])).first()
            review(db,doc,decision='REVOKED',manifest=False);db.commit()
        return value
    injected_model.generate=revoked
    assert call('model-execute').status_code==409
    with wire.evidence_session() as db:assert db.get(ExplorationJob,job).model_response is None


def test_real_conversation_consumer_selects_and_pins_dialogue_prompt_before_claim(scenario,monkeypatch):
    import json
    from app.exploration_model import ExplorationModel
    body,call=scenario;conversation_request(body)
    monkeypatch.setenv('EXPLORATION_MODEL_EXECUTION_ENABLED','true');monkeypatch.setenv('MOCK_MODE','false')
    monkeypatch.setenv('LLM_API_KEY','SYNTHETIC_NOT_A_KEY');monkeypatch.setenv('EXPLORATION_LLM_MODEL','TEST_CONVERSATION_MODEL')
    class Injected:
        def __init__(self,**settings):pass
        def with_structured_output(self,schema):return self
        async def ainvoke(self,messages):
            value=json.loads(messages[1].content)
            assert value['input']['conversation']==body['request']['input']['conversation']
            assert 'Answer input.conversation.question in summary' in messages[0].content
            return {'summary':'SYNTHETIC current-question answer','questions':[],'referenceKeys':[value['evidence'][0]['referenceKey']]}
    monkeypatch.setattr(wire,'ExplorationModel',lambda:ExplorationModel(Injected))
    assert call('enqueue').status_code==200
    response=call('model-execute');assert response.status_code==200
    assert response.json()['providerMetadata']['promptVersion']=='EXPLORATION_CONVERSATION_V1_0'
    assert call('model-execute').json()==response.json()


@pytest.fixture
def plan12_model(scenario,monkeypatch):
    import json
    from app.exploration_model import ExplorationModel
    body,call=scenario
    body['request']['purpose']='PLAN';body['request']['contractVersion']='EXPLORATION_AI_CONTRACT_V1_2'
    body['request']['input']['targetName']=body['request']['input']['candidateNames'][0]
    body['request']['input']['planSpec']={'weeks':12,'phaseLayout':'INFO_4_BASIC_4_ACTIVITY_4'}
    body['request']['inputHash']=input_hash(body['request'])
    monkeypatch.setenv('EXPLORATION_MODEL_EXECUTION_ENABLED','true');monkeypatch.setenv('MOCK_MODE','false')
    monkeypatch.setenv('LLM_API_KEY','SYNTHETIC_NOT_A_KEY');monkeypatch.setenv('EXPLORATION_LLM_MODEL','TEST_PLAN12_MODEL')
    class Injected:
        calls=0
        def __init__(self,**settings):assert settings['max_tokens']==6000
        def with_structured_output(self,schema):return self
        async def ainvoke(self,messages):
            Injected.calls+=1;value=json.loads(messages[1].content);assert 'exactly weeks 1-12' in messages[0].content
            from plan12_draft_fixtures import draft_result
            return draft_result(value['evidence'][0]['referenceKey'],value['input']['targetName'])
    monkeypatch.setattr(wire,'ExplorationModel',lambda:ExplorationModel(Injected))
    return body,call,Injected


def test_plan12_model_transport_preserves_target_weeks_and_pinned_prompt(plan12_model):
    body,call,model=plan12_model;assert call('enqueue').status_code==200
    result=call('model-execute');assert result.status_code==200
    assert result.json()['providerMetadata']['promptVersion']=='EXPLORATION_PLAN12_V1_4'
    assert len(result.json()['response']['result']['weeks'])==12
    assert call('model-execute').json()==result.json() and model.calls==1


def test_plan12_cancel_does_not_replay_generated_output(plan12_model):
    body,call,model=plan12_model;call('enqueue');assert call('model-execute').status_code==200
    assert call('cancel').status_code==200
    assert call('model-execute').status_code==409 and model.calls==1
