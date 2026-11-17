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
    assert ExplorationModel(chat).metadata['promptVersion']=='EXPLORATION_MODEL_V1_0'
    consumer=ExplorationModel(chat);consumer.configure_request(request)
    assert consumer.metadata['promptVersion']=='EXPLORATION_CONVERSATION_V1_0'


def test_v0_fixture_keeps_identical_shape_and_hash():
    request, _ = bound()
    assert read_request(request).model_dump() == request
    assert 'conversation' not in read_request(request).model_dump()['input']
