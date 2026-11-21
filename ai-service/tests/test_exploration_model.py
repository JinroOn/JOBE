import asyncio
import json
import pytest
from app.exploration_model import ExplorationModel,ModelFault
from app.exploration_contract import EXPLANATION_VERSION, input_hash
from test_exploration_evidence_binding import request

REF='ref_'+'a'*64


@pytest.fixture
def settings(monkeypatch):
    for name,value in {'EXPLORATION_MODEL_EXECUTION_ENABLED':'true','MOCK_MODE':'false','LLM_PROVIDER':'openai','EXPLORATION_LLM_MODEL':'TEST_MODEL','LLM_API_KEY':'SYNTHETIC_NOT_A_REAL_KEY','EXPLORATION_LLM_TIMEOUT_SECONDS':'1'}.items():monkeypatch.setenv(name,value)


def bound(purpose='CONSULTATION'):
    value=request(['TEST'],purpose=purpose,target='TEST' if purpose=='PLAN' else None)
    value['requestId']='PRIVATE_REQUEST_IDENTIFIER'
    value['evidence']={'approvedOnly':True,'bindingStatus':'BOUND','datasetVersion':'TEST_MODEL_V1','referenceKeys':[REF]};value['inputHash']=input_hash(value)
    facts=[{'referenceKey':REF,'majorName':'TEST','chunkType':'description','text':'SYNTHETIC APPROVED EDUCATIONAL FACT'}]
    return value,facts


class FakeChat:
    def __init__(self,result):self.result=result;self.messages=None;self.settings=None
    def __call__(self,**settings):self.settings=settings;return self
    def with_structured_output(self,schema):self.schema=schema;return self
    async def ainvoke(self,messages):self.messages=messages;return self.result


@pytest.mark.parametrize('connects', [True, False])
def test_major_explanation_uses_bound_facts_and_can_decline(settings, connects):
    value, facts = bound()
    signal = {'kind': 'ACTIVITY', 'metricCode': 'ACT_DES', 'questionId': 'ACT-DES-01',
              'questionText': '설계에 관심이 있나요?', 'answerText': '보통이에요.',
              'direction': 'NEUTRAL', 'validResponseCount': 1, 'inconsistent': False}
    value.update(contractVersion=EXPLANATION_VERSION, purpose='EXPLANATION',
                 input={'targetName': 'TEST', 'signals': [signal]})
    value['inputHash'] = input_hash(value)
    connections = [{'questionId': 'ACT-DES-01', 'connection': '설계 활동을 탐색해 볼 수 있어요.',
                    'referenceKeys': [REF]}] if connects else []
    chat = FakeChat({'targetName': 'TEST', 'connections': connections})
    model = ExplorationModel(chat)
    output = model.generate(value, facts)
    assert output['status'] == ('SUCCEEDED' if connects else 'DECLINED')
    assert output['result'] == ({'targetName': 'TEST', 'connections': connections} if connects else None)
    assert json.loads(chat.messages[1].content)['input']['signals'] == [signal]
    assert 'Treat neutral' in chat.messages[0].content
    assert model.metadata['promptVersion'] == 'EXPLORATION_MAJOR_EXPLANATION_V1_0'


@pytest.mark.parametrize('purpose',['CONSULTATION','PLAN'])
def test_structured_consumer_uses_minimal_data_and_server_binding(settings,purpose):
    value,facts=bound(purpose)
    result={'summary':'TEST generated guidance','questions':[],'referenceKeys':[REF]} if purpose=='CONSULTATION' else {'targetName':'TEST','overview':'TEST plan','steps':[{'title':'Read','description':'Review educational facts','referenceKeys':[REF]}]}
    chat=FakeChat(result);consumer=ExplorationModel(chat);response=consumer.generate(value,facts)
    assert response['inputHash']==value['inputHash'] and response['purpose']==purpose
    assert response['changesRecommendation'] is False
    data=json.loads(chat.messages[1].content)
    assert set(data)=={'purpose','input','evidence'} and data['evidence']==facts
    text=' '.join(m.content for m in chat.messages)
    assert 'PRIVATE_REQUEST_IDENTIFIER' not in text and value['sourceContextHash'] not in text
    assert 'SYNTHETIC_NOT_A_REAL_KEY' not in text
    assert chat.settings['max_retries']==0 and chat.settings['max_tokens']==1600
    assert set(consumer.metadata)=={'provider','model','promptVersion'}


@pytest.mark.parametrize('field,value',[('EXPLORATION_MODEL_EXECUTION_ENABLED','false'),('MOCK_MODE','true'),('LLM_PROVIDER','unsupported'),('EXPLORATION_LLM_TIMEOUT_SECONDS','0'),('EXPLORATION_LLM_TIMEOUT_SECONDS','6'),('EXPLORATION_LLM_TIMEOUT_SECONDS','nan')])
def test_invalid_configuration_never_constructs_a_network_model(settings,monkeypatch,field,value):
    monkeypatch.setenv(field,value)
    with pytest.raises(ModelFault) as error:ExplorationModel(lambda **k:pytest.fail('No model call'))
    assert error.value.code=='MODEL_CONFIGURATION_INVALID' and not error.value.retryable


def test_missing_key_never_falls_back_to_mock(settings,monkeypatch):
    for name in ['LLM_API_KEY','OPENAI_API_KEY','FACTCHAT_API_KEY']:monkeypatch.delenv(name,raising=False)
    with pytest.raises(ModelFault):ExplorationModel()


@pytest.mark.parametrize('change',['reference','extra','target'])
def test_invalid_output_is_permanent_and_not_returned(settings,change):
    value,facts=bound('PLAN' if change=='target' else 'CONSULTATION')
    result={'summary':'TEST','questions':[],'referenceKeys':['forged' if change=='reference' else REF]}
    if change=='extra':result['score']=99
    if change=='target':result={'targetName':'OTHER','overview':'TEST','steps':[{'title':'T','description':'D','referenceKeys':[REF]}]}
    with pytest.raises(ModelFault) as error:ExplorationModel(FakeChat(result)).generate(value,facts)
    assert error.value.code=='MODEL_OUTPUT_INVALID' and not error.value.retryable


def test_timeout_is_bounded_and_retryable(settings,monkeypatch):
    monkeypatch.setenv('EXPLORATION_LLM_TIMEOUT_SECONDS','0.01')
    class Slow(FakeChat):
        async def ainvoke(self,messages):await asyncio.sleep(10)
    with pytest.raises(ModelFault) as error:ExplorationModel(Slow(None)).generate(*bound())
    assert error.value.code=='MODEL_TIMEOUT' and error.value.retryable


@pytest.mark.parametrize('status,retryable',[(401,False),(403,False),(400,False),(429,True),(503,True)])
def test_provider_failures_are_redacted(settings,status,retryable):
    class Failure(Exception):status_code=status
    class Broken(FakeChat):
        async def ainvoke(self,messages):raise Failure('PRIVATE_API_KEY_AND_BODY')
    with pytest.raises(ModelFault) as error:ExplorationModel(Broken(None)).generate(*bound())
    assert error.value.retryable is retryable and 'PRIVATE' not in str(error.value)


def test_unbound_or_audit_enriched_facts_never_reach_model(settings):
    value,facts=bound();facts[0]['privateAudit']='DO_NOT_SEND'
    with pytest.raises(ModelFault) as error:ExplorationModel(lambda **kw:pytest.fail('Must not call model')).generate(value,facts)
    assert error.value.code=='MODEL_INPUT_INVALID'


def test_client_resources_are_closed_after_output(settings):
    closed=[]
    class AsyncClient:
        async def close(self):closed.append('async')
    class SyncClient:
        def close(self):closed.append('sync')
    chat=FakeChat({'summary':'TEST','questions':[],'referenceKeys':[REF]})
    chat.root_async_client=AsyncClient();chat.root_client=SyncClient()
    ExplorationModel(chat).generate(*bound());assert closed==['async','sync']
