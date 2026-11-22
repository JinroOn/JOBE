import asyncio
import json
import pytest
from pydantic import ValidationError
from app.exploration_model import ExplanationOutputIssue, ExplorationModel, ModelFault
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
    def __init__(self,result):self.result=result;self.messages=None;self.settings=None;self.calls=0
    def __call__(self,**settings):self.settings=settings;return self
    def with_structured_output(self,schema):self.schema=schema;return self
    async def ainvoke(self,messages):self.messages=messages;self.calls+=1;return self.result


def explanation_bound():
    value, facts = bound()
    signal = {'kind': 'ACTIVITY', 'metricCode': 'ACT_DES', 'questionId': 'ACT-DES-01',
              'questionText': '직접 설계하는 활동에 관심이 있나요?', 'answerText': '관심이 있어요.',
              'direction': 'POSITIVE', 'validResponseCount': 1, 'inconsistent': False}
    value.update(contractVersion=EXPLANATION_VERSION, purpose='EXPLANATION',
                 input={'targetName': 'TEST', 'signals': [signal]})
    facts[0]['text'] = '전공에서는 설계 활동을 진행한다. 설계 과정의  계획을 설명한다.'
    value['inputHash'] = input_hash(value)
    return value, facts


@pytest.mark.parametrize(('outcome', 'issue'), [
    ('supported', None),
    ('second-answer', None),
    ('no-match', None),
    ('unknown-excerpt', ExplanationOutputIssue.EXCERPT),
    ('provider-rejected-excerpt', ExplanationOutputIssue.EXCERPT),
    ('wrong-answer', ExplanationOutputIssue.UNKNOWN_ANSWER),
    ('provider-rejected-answer', ExplanationOutputIssue.UNKNOWN_ANSWER),
    ('duplicate-answer', ExplanationOutputIssue.DUPLICATE_ANSWER),
    ('wrong-target', ExplanationOutputIssue.TARGET),
    ('wrong-shape', ExplanationOutputIssue.DRAFT_SCHEMA),
])
def test_major_explanation_uses_bound_facts_and_reports_failed_condition(settings, outcome, issue):
    value, facts = explanation_bound()
    if outcome in {'second-answer', 'duplicate-answer'}:
        value['input']['signals'].append(dict(value['input']['signals'][0], questionId='ACT-DES-02'))
    value['inputHash'] = input_hash(value)
    draft = [{'answerChoice': 'A2' if outcome == 'second-answer' else 'A1',
              'evidenceChoice': 'E9' if outcome in {'unknown-excerpt', 'provider-rejected-excerpt'} else 'E1',
              'responseTopic': '직접 설계하는 활동', 'majorTopic': '설계 활동',
              'reason': '직접 설계하는 활동에 보인 관심은 전공의 설계 활동을 살펴볼 단서예요. 직접 계획해 보는 일이 두 내용과 연결돼요.',
              }] if outcome != 'no-match' else []
    if outcome in {'wrong-answer', 'provider-rejected-answer'}:
        draft[0]['answerChoice'] = 'A2'
    if outcome == 'duplicate-answer':
        draft.append(draft[0].copy())
    if outcome == 'wrong-shape':
        draft[0].pop('evidenceChoice')
    class ValidatingChat(FakeChat):
        async def ainvoke(self, messages):
            return self.schema.model_validate(await super().ainvoke(messages))

    chat_type = ValidatingChat if outcome.startswith('provider-rejected-') else FakeChat
    chat = chat_type({'targetName': 'OTHER' if outcome == 'wrong-target' else 'TEST', 'connections': draft})
    model = ExplorationModel(chat)
    if issue is not None:
        with pytest.raises(ModelFault) as error:
            model.generate(value, facts)
        assert error.value.code == 'MODEL_OUTPUT_INVALID'
        assert error.value.output_issue == issue and not error.value.retryable
        assert chat.calls == (2 if outcome in {'unknown-excerpt', 'provider-rejected-excerpt', 'wrong-shape', 'duplicate-answer'} else 1)
        return
    output = model.generate(value, facts)
    connection = {'questionId': 'ACT-DES-02' if outcome == 'second-answer' else 'ACT-DES-01',
                  'referenceKeys': [REF],
                  'connection': '“직접 설계하는 활동” 관련 문항에 “관심이 있어요.”라고 답했어요. '
                                '직접 설계하는 활동에 보인 관심은 전공의 설계 활동을 살펴볼 단서예요. '
                                '직접 계획해 보는 일이 두 내용과 연결돼요. '
                                '전공 자료에는 “전공에서는 설계 활동을 진행한다.”라고 나와요.'}
    assert output['status'] == ('SUCCEEDED' if draft else 'DECLINED')
    assert output['result'] == ({'targetName': 'TEST', 'connections': [connection]} if draft else None)
    provider_signals = json.loads(chat.messages[1].content)['input']['signals']
    assert [item['answerChoice'] for item in provider_signals] == [
        f'A{index}' for index in range(1, len(value['input']['signals']) + 1)
    ]
    assert all('questionId' not in item for item in provider_signals)
    assert json.loads(chat.messages[1].content)['evidence'] == [
        {'evidenceChoice': 'E1', 'text': '전공에서는 설계 활동을 진행한다.'},
        {'evidenceChoice': 'E2', 'text': '설계 과정의  계획을 설명한다.'},
    ]
    with pytest.raises(ValidationError):
        chat.schema.model_validate({'targetName': 'TEST', 'connections': [{
            'answerChoice': 'A3', 'evidenceChoice': 'E1',
            'responseTopic': '직접 설계하는 활동', 'majorTopic': '설계 활동',
            'reason': '직접 설계하는 활동에 보인 관심은 전공의 설계 활동을 살펴볼 단서예요.',
        }]})
    if len(provider_signals) == 1:
        item = {'answerChoice': 'A1', 'evidenceChoice': 'E1', 'responseTopic': '직접 설계하는 활동',
                'majorTopic': '설계 활동', 'reason': '직접 설계하는 활동과 전공 설계 활동을 함께 살펴볼 단서예요.'}
        with pytest.raises(ValidationError):
            chat.schema.model_validate({'targetName': 'TEST', 'connections': [item, item]})
    assert 'For a single response' in chat.messages[0].content
    assert 'Use each answerChoice at most once' in chat.messages[0].content
    assert chat.settings['timeout'] == 12
    assert model.metadata['promptVersion'] == 'EXPLORATION_MAJOR_EXPLANATION_V1_5'


@pytest.mark.parametrize('first_failure', ['parse', 'excerpt', 'transport-on-retry'])
def test_explanation_recovery_reuses_request_only_once(settings, first_failure):
    from langchain_core.exceptions import OutputParserException

    value, facts = explanation_bound()

    class RecoveringChat(FakeChat):
        async def ainvoke(self, messages):
            await super().ainvoke(messages)
            choice = 'E2'
            if self.calls == 1:
                if first_failure == 'parse':
                    raise OutputParserException('synthetic malformed output')
                choice = 'E9'
            else:
                assert 'Correct the output format' in messages[0].content
                if first_failure == 'transport-on-retry':
                    raise TimeoutError()
            return {'targetName': 'TEST', 'connections': [{
                'answerChoice': 'A1', 'evidenceChoice': choice, 'responseTopic': '직접 설계하는 활동',
                'majorTopic': '설계 과정',
                'reason': '직접 설계하는 활동에 보인 관심은 전공의 설계 과정에서 계획을 어떻게 세우는지 살펴볼 단서예요. 만들기 전에 순서를 생각해 보는 점이 이어져요.',
            }]}

    chat = RecoveringChat(None)
    model = ExplorationModel(chat)
    if first_failure == 'transport-on-retry':
        with pytest.raises(ModelFault) as error:
            model.generate(value, facts)
        assert error.value.output_issue == ExplanationOutputIssue.EXCERPT
        assert not error.value.retryable
    else:
        output = model.generate(value, facts)
        assert '설계 과정의  계획을 설명한다.' in output['result']['connections'][0]['connection']
        assert output['inputHash'] == value['inputHash']
    assert chat.calls == 2


@pytest.mark.parametrize(('direction', 'inconsistent'), [
    ('NEUTRAL', False), ('NEGATIVE', False), ('POSITIVE', True),
])
def test_no_affirmative_consistent_signal_declines_without_provider_call(settings, direction, inconsistent):
    value, facts = explanation_bound()
    value['input']['signals'][0].update(direction=direction, inconsistent=inconsistent)
    value['inputHash'] = input_hash(value)
    chat = FakeChat(None)
    result = ExplorationModel(chat).generate(value, facts)
    assert result['status'] == 'DECLINED'
    assert result['result'] is None
    assert chat.calls == 0


@pytest.mark.parametrize('tamper', [
    {'responseTopic': '실험 활동'},
    {'majorTopic': '로봇 제작'},
    {'reason': '전공의 설계 활동과 직접 설계하는 활동에 대한 답으로 합격 가능성을 판단할 수 있어요.'},
    {'reason': '설계 활동과 연결됩니다.'},
    {'reason': '직접 설계하는 활동에 보인 관심은 전공의 설계 활동을 살펴볼 단서예요. 다양한 매체를 써서 설계를 표현하는 점이 연결돼요.'},
])
def test_explanation_needs_real_answer_and_approved_topic_and_rejects_fit_claims(settings, tamper):
    value, facts = explanation_bound()
    connection = {'answerChoice': 'A1', 'evidenceChoice': 'E1', 'responseTopic': '직접 설계하는 활동',
                  'majorTopic': '설계 활동',
                  'reason': '직접 설계하는 활동에 보인 관심은 전공에서 안내한 설계 활동을 탐색할 구체적인 단서예요. 모양을 그려 보고 만들 순서를 정하는 점이 연결돼요.',
                  **tamper}
    chat = FakeChat({'targetName': 'TEST', 'connections': [connection]})
    with pytest.raises(ModelFault) as error:
        ExplorationModel(chat).generate(value, facts)
    assert error.value.output_issue == ExplanationOutputIssue.GROUNDING
    assert chat.calls == 2


def test_explanation_uses_two_approved_topics_but_omits_duplicate_major_element(settings):
    value, facts = explanation_bound()
    interest = value['input']['signals'][0]
    value['input']['signals'].append(dict(interest, questionId='ACT-DES-02'))
    reference = 'ref_' + 'b' * 64
    value['evidence']['referenceKeys'].append(reference)
    value['inputHash'] = input_hash(value)
    facts.append({'referenceKey': reference, 'majorName': 'TEST', 'chunkType': 'major_overview',
                  'text': '교육과정에서 설계 활동과 재료 실험을 다룬다.'})
    connection = {'answerChoice': 'A1', 'evidenceChoice': 'E1', 'responseTopic': '직접 설계하는 활동',
                  'majorTopic': '설계 활동',
                  'reason': '직접 설계하는 활동에 보인 관심은 전공에서 안내한 설계 활동을 탐색할 구체적인 단서예요. 모양을 그려 보고 만들 순서를 정하는 점이 연결돼요.'}
    chat = FakeChat({'targetName': 'TEST', 'connections': [
        connection, dict(connection, answerChoice='A2', evidenceChoice='E3')]})
    result = ExplorationModel(chat).generate(value, facts)['result']
    assert len(result['connections']) == 1
    transmitted = json.loads(chat.messages[1].content)['evidence']
    assert transmitted[-1]['text'] == facts[1]['text']


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
