import copy,json
from pathlib import Path
import pytest
from app.exploration_contract import read_request,validate_response,ContractViolation,input_hash
from app.exploration_model import ExplorationModel
from plan12_draft_fixtures import draft_result
from test_exploration_model import settings,FakeChat,bound

CASES=json.loads((Path(__file__).resolve().parents[2]/'datasets/exploration-plan12-contract/cases.json').read_text(encoding='utf-8'))


@pytest.mark.parametrize('case',CASES['requestCases'],ids=lambda c:c['name'])
def test_shared_plan12_request(case):
    if case['valid']:assert read_request(case['request']).model_dump()==case['request']
    else:
        with pytest.raises(ContractViolation):read_request(case['request'])

@pytest.mark.parametrize('case',CASES['responseCases'],ids=lambda c:c['name'])
def test_shared_plan12_response(case):
    if case['valid']:validate_response(CASES['request'],case['response'])
    else:
        with pytest.raises(ContractViolation):validate_response(CASES['request'],case['response'])

def test_model_uses_twelve_week_schema_prompt_budget_and_approved_references(settings):
    request,facts=bound('PLAN');request['contractVersion']='EXPLORATION_AI_CONTRACT_V1_2'
    request['input']['planSpec']={'weeks':12,'phaseLayout':'INFO_4_BASIC_4_ACTIVITY_4'};request['inputHash']=input_hash(request)
    content=draft_result(request['input']['targetName'])
    chat=FakeChat(content);consumer=ExplorationModel(chat);consumer.configure_request(request)
    assert consumer.metadata['promptVersion']=='EXPLORATION_PLAN12_V1_7'
    result=consumer.generate(request,facts)
    assert len(result['result']['weeks'])==12 and chat.settings['max_tokens']==6000
    assert 'exactly weeks 1-12' in chat.messages[0].content
    assert 'copy an exact substring' in chat.messages[0].content
    assert 'Never invent a URL' in chat.messages[0].content
    payload = json.loads(chat.messages[1].content)
    assert set(payload) == {'purpose', 'input', 'evidence'}
    assert 'referenceKey' not in payload['evidence'][0]
    assert all(week['referenceKeys'] == request['evidence']['referenceKeys'] for week in result['result']['weeks'])


def test_twelve_week_deadline_is_separate_from_short_consultation_deadline(settings,monkeypatch):
    monkeypatch.setenv('EXPLORATION_PLAN_LLM_TIMEOUT_SECONDS','30')
    request,facts=bound('PLAN');request['contractVersion']='EXPLORATION_AI_CONTRACT_V1_2'
    request['input']['planSpec']={'weeks':12,'phaseLayout':'INFO_4_BASIC_4_ACTIVITY_4'};request['inputHash']=input_hash(request)
    content=draft_result(request['input']['targetName'])
    chat=FakeChat(content);consumer=ExplorationModel(chat);consumer.generate(request,facts)
    assert chat.settings['timeout']==30 and chat.settings['max_retries']==0
    short=FakeChat({'summary':'TEST','questions':[],'referenceKeys':request['evidence']['referenceKeys']})
    ExplorationModel(short).generate(*bound())
    assert short.settings['timeout']==1


@pytest.mark.parametrize('value',['nan','inf','0','31'])
def test_unbounded_plan_deadline_cannot_construct_provider(settings,monkeypatch,value):
    from app.exploration_model import ModelFault
    monkeypatch.setenv('EXPLORATION_PLAN_LLM_TIMEOUT_SECONDS',value)
    with pytest.raises(ModelFault):ExplorationModel(lambda **k:pytest.fail('No model'))


def test_twelve_week_timeout_still_fails_without_partial_output(settings,monkeypatch):
    import asyncio
    from app.exploration_model import ModelFault
    monkeypatch.setenv('EXPLORATION_PLAN_LLM_TIMEOUT_SECONDS','.01')
    request,facts=bound('PLAN');request['contractVersion']='EXPLORATION_AI_CONTRACT_V1_2'
    request['input']['planSpec']={'weeks':12,'phaseLayout':'INFO_4_BASIC_4_ACTIVITY_4'};request['inputHash']=input_hash(request)
    class Slow(FakeChat):
        async def ainvoke(self,messages):await asyncio.sleep(10)
    with pytest.raises(ModelFault) as failure:ExplorationModel(Slow(None)).generate(request,facts)
    assert failure.value.code=='MODEL_TIMEOUT' and failure.value.retryable


@pytest.mark.parametrize('missing',['task','estimatedMinutes'])
def test_new_model_output_missing_draft_slots_is_rejected_without_repair(settings,missing):
    from app.exploration_model import ModelFault
    request,facts=bound('PLAN');request['contractVersion']='EXPLORATION_AI_CONTRACT_V1_2'
    request['input']['planSpec']={'weeks':12,'phaseLayout':'INFO_4_BASIC_4_ACTIVITY_4'};request['inputHash']=input_hash(request)
    content=draft_result(request['input']['targetName'])
    del content['weeks'][0][missing]
    original=copy.deepcopy(content)
    with pytest.raises(ModelFault) as error:ExplorationModel(FakeChat(content)).generate(request,facts)
    assert error.value.code=='MODEL_OUTPUT_INVALID' and not error.value.retryable
    assert content==original


def test_old_stored_plan_contract_remains_readable_without_new_presentation_labels():
    # The generation-only check must not tighten the shared/history wire validator.
    validate_response(CASES['request'],CASES['responseCases'][0]['response'])
