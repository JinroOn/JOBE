import copy
import json
from pathlib import Path

import pytest
from app.exploration_contract import (
    ContractViolation, read_request, input_hash, insufficient_evidence, validate_response,
)
from app.exploration_model import ExplorationModel
from test_exploration_model import FakeChat, bound, settings

VECTORS = json.loads((Path(__file__).resolve().parents[2] / 'datasets/exploration-conversation-contract/cases.json').read_text(encoding='utf-8'))


@pytest.mark.parametrize('case', VECTORS['requestCases'], ids=lambda c: c['name'])
def test_shared_conversation_contract(case):
    if case['valid']:
        assert read_request(case['request']).model_dump() == case['request']
    else:
        with pytest.raises(ContractViolation, match='^Invalid exploration AI contract$'):
            read_request(case['request'])


def test_conversation_has_its_own_version_and_preserves_reply_binding():
    request = VECTORS['requestCases'][0]['request']
    response = insufficient_evidence(request)
    assert validate_response(request, response).contractVersion == 'EXPLORATION_AI_CONTRACT_V1_1'
    response['contractVersion'] = 'EXPLORATION_AI_CONTRACT_V1_0'
    with pytest.raises(ContractViolation):
        validate_response(request, response)


def test_hash_binds_history_order_question_and_content_without_changing_context_hash():
    request = copy.deepcopy(VECTORS['requestCases'][1]['request'])
    original = request['inputHash']
    context = request['sourceContextHash']
    request['input']['conversation']['history'][0]['content'] += ' 수정'
    assert input_hash(request) != original
    assert request['sourceContextHash'] == context
    with pytest.raises(ContractViolation):
        read_request(request)


def test_injected_model_receives_question_and_history_as_data_not_new_evidence(settings):
    request, facts = bound()
    request['contractVersion'] = 'EXPLORATION_AI_CONTRACT_V1_1'
    request['input']['conversation'] = {
        'history': [{'role': 'user', 'content': 'SYNTHETIC previous question'},
                    {'role': 'assistant', 'content': 'SYNTHETIC previous answer, not approved evidence'}],
        'question': 'SYNTHETIC follow-up question',
    }
    request['inputHash'] = input_hash(request)
    chat = FakeChat({'summary': 'SYNTHETIC follow-up response', 'questions': [], 'referenceKeys': [facts[0]['referenceKey']]})
    response = ExplorationModel(chat).generate(request, facts)
    data = json.loads(chat.messages[1].content)
    assert data['input']['conversation'] == request['input']['conversation']
    assert data['evidence'] == facts
    assert response['contractVersion'] == request['contractVersion']
    assert response['inputHash'] == request['inputHash']
    assert 'PRIVATE_REQUEST_IDENTIFIER' not in chat.messages[1].content
    assert 'untrusted data' in chat.messages[0].content
    assert 'Answer input.conversation.question in summary' in chat.messages[0].content
    assert 'never as approved evidence' in chat.messages[0].content
    assert 'write one sentence without a follow-up' in chat.messages[0].content
    assert 'choose one supported example and present only it' in chat.messages[0].content
    assert 'A course title in a list supports its inclusion only' in chat.messages[0].content
    assert 'the provided material does not describe it' in chat.messages[0].content
    assert 'at most one short, optional follow-up question' in chat.messages[0].content
    assert 'Do not repeat a follow-up already asked' in chat.messages[0].content
    assert 'Omit the follow-up if the answer is sufficient' in chat.messages[0].content
    assert 'Keep questions empty; only summary is shown' in chat.messages[0].content
    assert ExplorationModel(chat).metadata['promptVersion']=='EXPLORATION_MODEL_V1_0'
    consumer=ExplorationModel(chat);consumer.configure_request(request)
    assert consumer.metadata['promptVersion']=='EXPLORATION_CONVERSATION_V1_3'


def test_v0_fixture_keeps_identical_shape_and_hash():
    request, _ = bound()
    assert read_request(request).model_dump() == request
    assert 'conversation' not in read_request(request).model_dump()['input']


@pytest.mark.parametrize('question', ['고마워', '오늘 서울 날씨 알려줘', '자료에 없는 수업의 필수 요건을 알려줘'])
def test_uncited_conversation_draft_becomes_a_safe_decline(settings, question):
    request, facts = bound()
    request['contractVersion'] = 'EXPLORATION_AI_CONTRACT_V1_1'
    request['input']['conversation'] = {'history': [], 'question': question}
    request['inputHash'] = input_hash(request)
    chat = FakeChat({'summary': '이 문장은 학생에게 노출하면 안 되는 미검증 문장입니다.', 'questions': [], 'referenceKeys': []})

    response = ExplorationModel(chat).generate(request, facts)

    assert response['status'] == 'DECLINED'
    assert response['reasonCode'] == 'OUT_OF_SCOPE'
    assert response['result'] is None
    with pytest.raises(ContractViolation):
        validate_response(request, {**response, 'status': 'SUCCEEDED', 'reasonCode': None, 'result': chat.result})


@pytest.mark.parametrize('example, result_kind', [
    ('한문사료강독 1', 'example'),
    ('표본설계 및 조사실습', 'example'),
    ('회귀분석 및 실습', 'example'),
    (None, 'unavailable'),
    ('역사학 논문쓰기 1과 한문사료강독 1', 'invalid'),
    ('확률론 및 통계학', 'invalid'),
    ('표본설계 및 조사실습과 회귀분석 및 실습', 'invalid'),
    ('조선시대 연구 1', 'invalid'),
])
def test_one_learning_example_is_rendered_from_one_approved_span(settings, example, result_kind):
    from app.exploration_consultation_example import SingleExampleDraft
    from app.exploration_model import ModelFault

    request, facts = bound()
    request['contractVersion'] = 'EXPLORATION_AI_CONTRACT_V1_1'
    request['input']['conversation'] = {'history': [], 'question': '학습 예시를 하나만 알려 주세요.'}
    request['inputHash'] = input_hash(request)
    facts[0]['text'] = ('역사학 논문쓰기 1과 한문사료강독 1이 나온다. '
                       '표본설계 및 조사실습과 회귀분석 및 실습이 있다. 확률론 및 통계학도 확인할 수 있다.')
    reference = facts[0]['referenceKey']
    chat = FakeChat({'example': example, 'referenceKey': reference if example is not None else None})
    model = ExplorationModel(chat)

    if result_kind == 'invalid':
        with pytest.raises(ModelFault) as error:
            model.generate(request, facts)
        assert error.value.code == 'MODEL_OUTPUT_INVALID'
    elif result_kind == 'unavailable':
        response = model.generate(request, facts)
        assert response['status'] == 'INSUFFICIENT_EVIDENCE'
        assert response['result'] is None
    else:
        result = model.generate(request, facts)['result']
        assert not result['questions'] and result['referenceKeys'] == [reference]
        assert result['summary'].endswith(f'「{example}」')
        assert result['summary'].count('「') == result['summary'].count('」') == 1
    assert chat.schema is SingleExampleDraft
