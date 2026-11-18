from copy import deepcopy
import pytest

from app.exploration_contract import ContractViolation, Plan12Result
from app.exploration_model import ExplorationModel, ModelFault
from app.exploration_plan_draft import Plan12Draft, render_plan_draft
from plan12_draft_fixtures import draft_result
from test_exploration_model import FakeChat, bound, settings
from app.exploration_contract import input_hash


def model_request():
    request, facts = bound('PLAN')
    request['contractVersion'] = 'EXPLORATION_AI_CONTRACT_V1_2'
    request['input']['planSpec'] = {'weeks': 12, 'phaseLayout': 'INFO_4_BASIC_4_ACTIVITY_4'}
    request['inputHash'] = input_hash(request)
    return request, facts


def test_rendered_tasks_embed_the_actual_input_and_retain_public_shape():
    request, facts = model_request()
    draft = draft_result(facts[0]['referenceKey'])
    original = deepcopy(draft)
    rendered = render_plan_draft(draft, facts)
    assert set(rendered['weeks'][0]) == {'week', 'phase', 'title', 'tasks', 'expectedOutput', 'referenceKeys'}
    assert '[책, 공책, 펜]' in rendered['weeks'][0]['tasks'][1]
    assert '가상 예시:' in rendered['weeks'][0]['tasks'][1]
    assert '같은 입력으로' in rendered['weeks'][0]['tasks'][1]
    assert '단어 한 개' in rendered['weeks'][0]['tasks'][1]
    assert draft == original
    Plan12Result.model_validate(rendered)


@pytest.mark.parametrize('change', ['blank_input', 'blank_alternative', 'different_input', 'extra_tasks',
                                  'too_long_input', 'missing_output', 'reorder', 'wrong_phase',
                                  'string_minutes', 'zero_minutes', 'excessive_minutes', 'unknown_reference',
                                  'fabricated_excerpt', 'uncited_excerpt', 'public_only'])
def test_invalid_drafts_fail_without_partial_output_or_silent_repair(settings, change):
    request, facts = model_request()
    draft = draft_result(facts[0]['referenceKey'])
    week = draft['weeks'][0]
    if change == 'blank_input': week['inputText'] = '  '
    elif change == 'blank_alternative': week['easierAction'] = '  '
    elif change == 'different_input': week['easierInput'] = 'ANOTHER RESOURCE'
    elif change == 'extra_tasks': week['tasks'] = ['OLD FREE TEXT']
    elif change == 'too_long_input': week['inputText'] = 'x'*71
    elif change == 'missing_output': del week['expectedOutput']
    elif change == 'reorder': draft['weeks'][0], draft['weeks'][1] = draft['weeks'][1], draft['weeks'][0]
    elif change == 'wrong_phase': week['phase'] = 'SMALL_ACTIVITY'
    elif change == 'string_minutes': week['estimatedMinutes'] = '5'
    elif change == 'zero_minutes': week['estimatedMinutes'] = 0
    elif change == 'excessive_minutes': week['estimatedMinutes'] = 21
    elif change == 'unknown_reference': week['referenceKeys'] = ['forged']
    elif change == 'fabricated_excerpt': week.update(inputKind='EVIDENCE_EXCERPT', inputText='NOT PRESENT IN FACT')
    elif change == 'uncited_excerpt':
        facts.append(dict(facts[0], referenceKey='other', text='ONLY IN ANOTHER FACT'))
        # Test renderer separately: consumer correctly rejects an input with extra unrelated facts first.
        week.update(inputKind='EVIDENCE_EXCERPT', inputText='ONLY IN ANOTHER FACT')
        with pytest.raises(ContractViolation): render_plan_draft(draft, facts)
        return
    elif change == 'public_only':
        draft = render_plan_draft(draft, facts)
    original = deepcopy(draft)
    with pytest.raises(ModelFault) as failure:
        ExplorationModel(FakeChat(draft)).generate(request, facts)
    assert failure.value.code == 'MODEL_OUTPUT_INVALID' and not failure.value.retryable
    assert draft == original


def test_literal_excerpt_must_come_from_the_cited_fact(settings):
    request, facts = model_request()
    draft = draft_result(facts[0]['referenceKey'])
    draft['weeks'][0].update(inputKind='EVIDENCE_EXCERPT', inputText='APPROVED EDUCATIONAL')
    chat = FakeChat(draft)
    result = ExplorationModel(chat).generate(request, facts)
    assert chat.schema is Plan12Draft
    assert '근거 발췌: APPROVED EDUCATIONAL' in result['result']['weeks'][0]['tasks'][1]


def test_maximum_draft_field_sizes_still_fit_existing_wire_limits():
    request, facts = model_request()
    draft = draft_result(facts[0]['referenceKey'])
    draft['weeks'][0].update(materials='가'*35, inputText='나'*70, action='다'*40,
                             expectedOutput='라'*50, easierAction='마'*30, easierOutput='바'*30)
    rendered = render_plan_draft(draft, facts)
    assert max(map(len, rendered['weeks'][0]['tasks'])) <= 300
    assert rendered['weeks'][0]['expectedOutput'] == '라'*50
