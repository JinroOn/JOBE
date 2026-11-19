from copy import deepcopy
from types import SimpleNamespace
import pytest
from pydantic import ValidationError

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


class DiagnosticChat(FakeChat):
    def with_structured_output(self, schema, *, include_raw):
        assert include_raw
        return super().with_structured_output(schema)


def test_parser_diagnostics_only_keep_known_paths_and_error_codes(settings):
    request, facts = model_request()
    draft = draft_result(facts[0]['referenceKey'])
    draft['weeks'][0]['estimatedMinutes'] = 'PRIVATE_VALUE'
    draft['weeks'][0]['PRIVATE_FIELD'] = 'PRIVATE_VALUE'
    with pytest.raises(ValidationError) as parser_failure:
        Plan12Draft.model_validate(draft)

    model = ExplorationModel(DiagnosticChat({
        'parsed': None, 'parsing_error': parser_failure.value,
        'raw': SimpleNamespace(content='PRIVATE_RAW', response_metadata={'finish_reason': 'tool_calls'}),
    }), evaluation_diagnostics=True)
    with pytest.raises(ModelFault) as failure:
        model.generate(request, facts)

    assert failure.value.code == 'MODEL_OUTPUT_INVALID' and not failure.value.retryable
    assert model.timings['validationAndRenderMs'] is None
    assert model.output_diagnostic == {
        'stage': 'PROVIDER_PARSING', 'kind': 'SCHEMA_VALIDATION', 'finishReason': 'tool_calls',
        'errorCount': 2, 'errors': [
            {'type': 'int_type', 'path': ['weeks', 0, 'estimatedMinutes']},
            {'type': 'extra_forbidden', 'path': ['weeks', 0]},
        ],
    }


def test_diagnostic_mode_distinguishes_truncation_from_render_rejection(settings):
    request, facts = model_request()
    chat = DiagnosticChat({'parsed': None, 'parsing_error': None,
                           'raw': SimpleNamespace(response_metadata={'finish_reason': 'length'})})
    model = ExplorationModel(chat, evaluation_diagnostics=True)
    with pytest.raises(ModelFault):
        model.generate(request, facts)
    assert model.output_diagnostic['kind'] == 'OUTPUT_LENGTH_LIMIT'

    draft = draft_result(facts[0]['referenceKey'])
    draft['weeks'][0].update(inputKind='EVIDENCE_EXCERPT', inputText='NOT IN SOURCE')
    chat.result = {'parsed': Plan12Draft.model_validate(draft), 'parsing_error': None, 'raw': None}
    with pytest.raises(ModelFault):
        model.generate(request, facts)
    assert model.output_diagnostic == {'stage': 'VALIDATION_AND_RENDER', 'kind': 'CONTRACT_VALIDATION'}

    chat.result['parsed'] = Plan12Draft.model_validate(draft_result(facts[0]['referenceKey']))
    response = model.generate(request, facts)
    assert len(response['result']['weeks']) == 12
    assert model.output_diagnostic is None and 'outputDiagnostic' not in response
