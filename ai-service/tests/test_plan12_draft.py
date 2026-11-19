from copy import deepcopy
from types import SimpleNamespace
import pytest
from langchain_core.utils.function_calling import convert_to_openai_tool
from pydantic import ValidationError

from app.exploration_contract import ContractViolation, Plan12Result
from app.exploration_model import ExplorationModel, ModelFault
from app.exploration_plan_draft import Plan12Draft, excerpt_candidates, render_plan_draft
from app.exploration_plan_scenarios import FICTIONAL_SCENARIOS
from plan12_draft_fixtures import draft_result
from test_exploration_model import FakeChat, bound, settings
from app.exploration_contract import input_hash


def model_request():
    request, facts = bound('PLAN')
    request['contractVersion'] = 'EXPLORATION_AI_CONTRACT_V1_2'
    request['input']['planSpec'] = {'weeks': 12, 'phaseLayout': 'INFO_4_BASIC_4_ACTIVITY_4'}
    request['inputHash'] = input_hash(request)
    return request, facts


def excerpt_task(index, activity='MARK_KEYWORD'):
    return {'inputKind': 'EVIDENCE_EXCERPT', 'excerptIndex': index, 'activity': activity}


def test_provider_tool_schema_describes_both_allowed_task_shapes():
    parameters = convert_to_openai_tool(Plan12Draft)['function']['parameters']
    fields = parameters['properties']['weeks']['items']['properties']
    assert 'referenceKeys' not in fields
    branches = fields['task']['anyOf']
    by_kind = {item['properties']['inputKind']['const']: item for item in branches}
    evidence = by_kind['EVIDENCE_EXCERPT']
    fictional = by_kind['FICTIONAL_EXAMPLE']
    assert set(evidence['required']) == {'inputKind', 'excerptIndex', 'activity'}
    assert set(evidence['properties']['activity']['enum']) == {'MARK_KEYWORD', 'WRITE_QUESTION'}
    assert set(fictional['required']) == {'inputKind', 'scenarioIndex'}
    assert fictional['properties']['scenarioIndex']['maximum'] == len(FICTIONAL_SCENARIOS)
    assert all(branch['additionalProperties'] is False for branch in branches)


def test_rendered_tasks_embed_the_actual_input_and_retain_public_shape():
    request, facts = model_request()
    draft = draft_result()
    original = deepcopy(draft)
    rendered = render_plan_draft(draft, facts)
    assert set(rendered['weeks'][0]) == {'week', 'phase', 'title', 'tasks', 'expectedOutput', 'referenceKeys'}
    assert '가상 예시: 「문구류를 찾기 쉽게 정리하려고 한다.' in rendered['weeks'][0]['tasks'][1]
    assert 'A 「재료별로 나누기」 / B 「쓰는 목적별로 나누기」' in rendered['weeks'][0]['tasks'][1]
    assert 'A 또는 B 한 글자' in rendered['weeks'][0]['tasks'][1]
    assert rendered['weeks'][0]['expectedOutput'] == '고른 선택지 하나와 고른 이유 한 문장'
    assert draft == original
    Plan12Result.model_validate(rendered)


@pytest.mark.parametrize('change', ['string_index', 'zero_index', 'excessive_index', 'missing_index',
                                  'excerpt_with_scenario', 'fictional_with_activity', 'extra_tasks',
                                  'model_written_scenario', 'reorder', 'wrong_phase',
                                  'string_minutes', 'zero_minutes', 'excessive_minutes', 'model_reference',
                                  'unavailable_excerpt', 'old_excerpt_text', 'public_only'])
def test_invalid_drafts_fail_without_partial_output_or_silent_repair(settings, change):
    request, facts = model_request()
    draft = draft_result()
    week = draft['weeks'][0]
    if change == 'string_index': week['task']['scenarioIndex'] = '1'
    elif change == 'zero_index': week['task']['scenarioIndex'] = 0
    elif change == 'excessive_index': week['task']['scenarioIndex'] = len(FICTIONAL_SCENARIOS) + 1
    elif change == 'missing_index': del week['task']['scenarioIndex']
    elif change == 'excerpt_with_scenario': week['task'] = {**excerpt_task(1), 'scenarioIndex': 1}
    elif change == 'fictional_with_activity': week['task']['activity'] = 'MARK_KEYWORD'
    elif change == 'extra_tasks': week['tasks'] = ['OLD FREE TEXT']
    elif change == 'model_written_scenario': week['task']['inputText'] = 'Unreviewed generic situation'
    elif change == 'reorder': draft['weeks'][0], draft['weeks'][1] = draft['weeks'][1], draft['weeks'][0]
    elif change == 'wrong_phase': week['phase'] = 'SMALL_ACTIVITY'
    elif change == 'string_minutes': week['estimatedMinutes'] = '5'
    elif change == 'zero_minutes': week['estimatedMinutes'] = 0
    elif change == 'excessive_minutes': week['estimatedMinutes'] = 21
    elif change == 'model_reference': week['referenceKeys'] = ['forged']
    elif change == 'unavailable_excerpt': week['task'] = excerpt_task(1)
    elif change == 'old_excerpt_text':
        week['task'] = {'inputKind': 'EVIDENCE_EXCERPT', 'inputText': 'NOT PRESENT IN FACT', 'activity': 'MARK_KEYWORD'}
    elif change == 'public_only':
        draft = render_plan_draft(draft, facts)
    original = deepcopy(draft)
    with pytest.raises(ModelFault) as failure:
        ExplorationModel(FakeChat(draft)).generate(request, facts)
    assert failure.value.code == 'MODEL_OUTPUT_INVALID' and not failure.value.retryable
    assert draft == original


def test_server_renders_the_displayed_candidate_without_clipping(settings):
    import json

    request, facts = model_request()
    opening = ('컴퓨터공학과에서는 컴퓨터 시스템과 프로그래밍, 알고리즘 설계와 응용 소프트웨어 개발 등 '
               '컴퓨터로 문제를 해결하는 여러 주제를 이론과 실습을 통해 다룬다.')
    assert len(opening) > 70
    facts[0]['text'] = opening + '  데이터베이스는 교과목의 한 예다.  끝나지 않은 조각'
    draft = draft_result()
    draft['weeks'][0]['task'] = excerpt_task(1)
    chat = FakeChat(draft)
    result = ExplorationModel(chat).generate(request, facts)
    assert chat.schema is Plan12Draft
    assert json.loads(chat.messages[1].content)['evidence'][0]['excerptCandidates'] == [
        {'index': 1, 'text': opening}, {'index': 2, 'text': '데이터베이스는 교과목의 한 예다.'},
    ]
    assert f'근거 발췌: 「{opening}」' in result['result']['weeks'][0]['tasks'][1]
    assert result['result']['weeks'][0]['referenceKeys'] == request['evidence']['referenceKeys']


def test_candidates_are_bounded_deduplicated_and_never_truncated():
    full = '가' * 119 + '.'
    too_long = '나' * 120 + '.'
    sentences = [f'교과목 사례 {i}번이다.' for i in range(1, 15)]
    source = ' '.join([too_long, full, full, '이어지는 조각...', *sentences, '끝나지 않은 글'])
    assert excerpt_candidates(source) == [full, *sentences[:11]]


def test_index_must_exist_in_the_current_approved_candidates():
    _, facts = model_request()
    facts[0]['text'] = '이 과목은 컴퓨터를 다룬다.'
    draft = draft_result()
    draft['weeks'][0]['task'] = excerpt_task(2)
    with pytest.raises(ContractViolation, match='excerpt index is not available'):
        render_plan_draft(draft, facts)


def test_plan_references_require_one_bound_fact_and_cannot_be_chosen_by_the_model(settings):
    request, facts = model_request()
    model = ExplorationModel(lambda **kwargs: pytest.fail('Invalid evidence must fail before provider call'))
    with pytest.raises(ModelFault) as failure:
        model.generate(request, [dict(facts[0], referenceKey='forged')])
    assert failure.value.code == 'MODEL_INPUT_INVALID'
    with pytest.raises(ContractViolation):
        render_plan_draft(draft_result(), facts * 2)


def test_all_curated_scenarios_fit_existing_wire_limits():
    _, facts = model_request()
    draft = draft_result()
    rendered = render_plan_draft(draft, facts)['weeks']
    rendered_by_scenario = {week['task']['scenarioIndex']: output for week, output in zip(draft['weeks'], rendered)}
    for scenario_index, scenario in enumerate(FICTIONAL_SCENARIOS, 1):
        week = rendered_by_scenario.get(scenario_index)
        if week is None:
            draft['weeks'][0]['task']['scenarioIndex'] = scenario_index
            week = render_plan_draft(draft, facts)['weeks'][0]
        assert max(map(len, week['tasks'])) <= 300
        assert all(text in week['tasks'][1] for text in (scenario.situation, scenario.first, scenario.second))


@pytest.mark.parametrize('number,title,output,easier', [
    (5, '두 칸으로 나누기', 'A와 B를 나눠 적은 두 칸', 'A가 들어갈 칸의 이름 하나'),
    (6, '차이 한 가지 찾기', 'A와 B의 차이를 적은 한 줄', '옮겨 적은 낱말이나 표시 하나'),
    (8, '두 행동에 순서 붙이기', '순서 번호가 붙은 행동 두 개', '1번을 붙인 행동 하나'),
    (10, '작은 화면 그리기', '이름을 적은 네모 두 개', 'A의 이름을 적은 네모 하나'),
])
def test_varied_activities_render_their_own_output_and_alternative(number, title, output, easier):
    _, facts = model_request()
    draft = draft_result()
    week = render_plan_draft(draft, facts)['weeks'][number - 1]
    source = FICTIONAL_SCENARIOS[draft['weeks'][number - 1]['task']['scenarioIndex'] - 1]
    assert week['title'] == title and week['expectedOutput'] == output
    assert f'(결과: {easier})' in week['tasks'][1]
    assert all(text in week['tasks'][1] for text in (source.situation, source.first, source.second))


def test_repeated_choices_cannot_replace_varied_activities_after_info(settings):
    request, facts = model_request()
    draft = draft_result()
    draft['weeks'][1]['task'], draft['weeks'][4]['task'] = draft['weeks'][4]['task'], draft['weeks'][1]['task']
    draft['weeks'][4]['task']['scenarioIndex'] = 12
    draft['weeks'][6]['task']['scenarioIndex'] = 13
    with pytest.raises(ValidationError) as failure:
        Plan12Draft.model_validate(draft)
    assert failure.value.errors()[0]['type'] == 'missing_activity_variety'
    with pytest.raises(ModelFault) as failure:
        ExplorationModel(FakeChat(draft)).generate(request, facts)
    assert failure.value.code == 'MODEL_OUTPUT_INVALID'


def test_curated_scenario_cannot_be_repeated():
    draft = draft_result()
    draft['weeks'][0]['task'] = draft['weeks'][1]['task'].copy()
    with pytest.raises(ValidationError) as failure:
        Plan12Draft.model_validate(draft)
    assert failure.value.errors()[0]['type'] == 'repeated_scenario'


@pytest.mark.parametrize('number,excerpt,allowed,prohibited,expected', [
    (4, 'IT 관련 글과 뉴스를 읽거나 관련 영상을 살펴볼 수 있다.', 'WRITE_QUESTION', 'IT 관련 기사 한 개 요약하기', '고른 말 하나와 질문 한 문장'),
    (9, '운영체제의 구조와 작동 방식을 알아본다.', 'MARK_KEYWORD', '운영체제 구조를 그려보기', '표시한 말 하나와 고른 이유 한 문장'),
])
def test_short_excerpts_cannot_be_used_to_assign_unsupported_work(settings, number, excerpt, allowed, prohibited, expected):
    request, facts = model_request()
    facts[0]['text'] = excerpt
    draft = draft_result()
    week = draft['weeks'][number - 1]
    week['task'] = excerpt_task(1, allowed)
    model = ExplorationModel(FakeChat(draft))
    rendered = model.generate(request, facts)['result']['weeks'][number - 1]
    assert f'「{excerpt}」' in rendered['tasks'][1]
    assert rendered['expectedOutput'] == expected
    assert '쉬운 대안: 발췌에서' in rendered['tasks'][1]

    week['task']['action'] = prohibited
    with pytest.raises(ModelFault) as failure:
        model.generate(request, facts)
    assert failure.value.code == 'MODEL_OUTPUT_INVALID'

    week['task'] = excerpt_task(1, 'CHOOSE_OPTION')
    with pytest.raises(ModelFault) as failure:
        model.generate(request, facts)
    assert failure.value.code == 'MODEL_OUTPUT_INVALID'


def test_nested_diagnostics_identify_known_paths_and_remove_arbitrary_keys():
    from app.plan_evaluation_diagnostics import output_failure

    draft = draft_result()
    draft['weeks'][0]['task'].update(scenarioIndex='4', PRIVATE_KEY='PRIVATE_VALUE')
    with pytest.raises(ValidationError) as failure:
        Plan12Draft.model_validate(draft)
    errors = output_failure('PROVIDER_PARSING', failure.value)['errors']
    assert {'type': 'int_type', 'path': ['weeks', 0, 'task', 'FictionalTask', 'scenarioIndex']} in errors
    assert 'PRIVATE' not in str(errors)

    draft['weeks'][0]['task'] = {
        'inputKind': 'FICTIONAL_EXAMPLE', 'scenarioIndex': 0,
    }
    with pytest.raises(ValidationError) as failure:
        Plan12Draft.model_validate(draft)
    errors = output_failure('PROVIDER_PARSING', failure.value)['errors']
    assert {'type': 'greater_than_equal', 'path': ['weeks', 0, 'task', 'FictionalTask', 'scenarioIndex']} in errors


class DiagnosticChat(FakeChat):
    def with_structured_output(self, schema, *, include_raw):
        assert include_raw
        return super().with_structured_output(schema)


def test_parser_diagnostics_only_keep_known_paths_and_error_codes(settings):
    request, facts = model_request()
    draft = draft_result()
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

    draft = draft_result()
    draft['weeks'][0]['task'] = excerpt_task(1)
    chat.result = {'parsed': Plan12Draft.model_validate(draft), 'parsing_error': None, 'raw': None}
    with pytest.raises(ModelFault):
        model.generate(request, facts)
    assert model.output_diagnostic == {'stage': 'VALIDATION_AND_RENDER', 'kind': 'CONTRACT_VALIDATION'}

    chat.result['parsed'] = Plan12Draft.model_validate(draft_result())
    response = model.generate(request, facts)
    assert len(response['result']['weeks']) == 12
    assert model.output_diagnostic is None and 'outputDiagnostic' not in response
