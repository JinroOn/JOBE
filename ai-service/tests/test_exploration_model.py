import asyncio
import json
import pytest
from pydantic import ValidationError
from app.exploration_model import (
    ExplanationOutputIssue, ExplorationModel, ModelFault, explains_grounded_connection, explanation_excerpts,
    supports_response, trimmed_topic,
)
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


@pytest.mark.parametrize(('model_name', 'expected_options'), [
    ('TEST_MODEL', None),
    ('gpt-6-luna', {'reasoning_effort': 'none'}),
])
def test_structured_chat_completions_use_compatible_reasoning(settings, monkeypatch, model_name, expected_options):
    monkeypatch.setenv('EXPLORATION_LLM_MODEL', model_name)
    request, facts = bound()
    chat = FakeChat({'summary': '근거를 먼저 살펴보세요.', 'questions': [], 'referenceKeys': [REF]})

    result = ExplorationModel(chat).generate(request, facts)

    assert result['status'] == 'SUCCEEDED'
    assert chat.settings.get('model_kwargs') == expected_options


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
                  'connection': '직접 설계하는 활동에 보인 관심은 전공의 설계 활동을 살펴볼 단서예요. '
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
    assert model.metadata['promptVersion'] == 'EXPLORATION_MAJOR_EXPLANATION_V1_12'


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
    {'reason': '직접 설계하는 활동에 보인 관심은 설계 활동과 이어져요. 전공에서도 설계 활동은 매우 중요한 단계입니다.'},
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
        connection, dict(connection, answerChoice='A2', evidenceChoice='E3', majorTopic='설계 활동과',
                         reason='직접 설계하는 활동은 설계 활동과 이어져요. 모양을 그려 보고 만들 순서를 정하는 점이 연결돼요.') ]})
    result = ExplorationModel(chat).generate(value, facts)['result']
    assert len(result['connections']) == 1
    transmitted = json.loads(chat.messages[1].content)['evidence']
    assert transmitted[-1]['text'] == facts[1]['text']


BUSINESS = '경영학과는 조직이 목표를 달성하도록 사람, 예산과 운영 방식을 관리하는 방법을 배운다.'
LIFE = '생명과학과는 생명체의 구성과 기능을 세포부터 생태계까지 다양한 수준에서 탐구한다.'


def explanation_with(question, facts_text, **draft):
    value, facts = explanation_bound()
    value['input']['signals'][0].update(questionText=question, answerText='매우 그렇다')
    value['inputHash'] = input_hash(value)
    facts[0]['text'] = facts_text
    connection = {'answerChoice': 'A1', 'evidenceChoice': 'E1', **draft}
    return value, facts, FakeChat({'targetName': 'TEST', 'connections': [connection]})


@pytest.mark.parametrize(('text', 'major_topic', 'reason_topic'), [
    (BUSINESS, '사람, 예산과 운영 방식 관리', '사람, 예산과 운영 방식 관리'),
    (LIFE, '생명체의 구성과 기능을 탐구한다.', '생명체의 구성과 기능을 탐구하는 일'),
    ('학과는 취재·제작 과정과 언론의 역할을 배운다.', '취재·제작 과정과 언론의 역할을 배운다.', '취재·제작 과정과 언론의 역할을 배우는 일'),
])
def test_explanation_drops_a_predicate_added_to_an_approved_topic(settings, text, major_topic, reason_topic):
    value, facts, chat = explanation_with(
        '조직이 운영되는 과정이 흥미롭다.', text, responseTopic='조직이 운영되는 과정', majorTopic=major_topic,
        reason=f'조직이 운영되는 과정에 보인 흥미는 {reason_topic}을 살펴볼 단서예요. 일이 이루어지는 흐름을 따라가는 점이 연결돼요.')
    result = ExplorationModel(chat).generate(value, facts)
    assert result['status'] == 'SUCCEEDED'
    assert f'전공 자료에는 “{text}”라고 나와요.' in result['result']['connections'][0]['connection']
    assert chat.calls == 1


@pytest.mark.parametrize(('evidence', 'connected'), [
    ('로드맵은 실생활 문제를 알고리즘으로 해결하는 교과를 소개한다.', False),
    ('전공은 관측 데이터를 수집하고 정리하는 방법을 배운다.', True),
])
def test_data_processing_interest_needs_evidence_of_data_processing(settings, evidence, connected):
    value, facts, chat = explanation_with(
        '데이터가 수집·정리되어 정보가 되는 과정을 배우고 싶다.', evidence,
        responseTopic='데이터가 수집·정리되어', majorTopic='관측 데이터를 수집하고 정리하는 방법',
        reason='데이터가 수집·정리되어 정보가 되는 과정에 보인 관심은 관측 데이터를 수집하고 정리하는 방법을 살펴볼 단서예요. 흩어진 관측값을 모아 읽을 수 있도록 정리하는 점이 이어져요.',
    )
    result = ExplorationModel(chat).generate(value, facts)

    assert result['status'] == ('SUCCEEDED' if connected else 'DECLINED')
    assert chat.calls == int(connected)


COMPUTER = '운영체제의 구조와 작동 방식, 소프트웨어의 요구사항 분석과 설계, 자료구조의 구현 등을 배우는 내용을 살펴볼 수 있다.'
PATTERN = '숫자나 자료를 비교해 패턴을 찾는 활동이 흥미롭다.'
CAUSE = '다양한 자료를 비교해 문제의 원인을 설명하는 활동에 끌린다.'
MEDIA = '미디어가 사람과 사회에 영향을 주는 방식을 배우고 싶다.'
PROCESS = '데이터가 수집·정리되어 정보가 되는 과정을 배우고 싶다.'
JUDGE = '정보를 분류하고 분석해 판단에 활용하는 방법을 배우고 싶다.'


@pytest.mark.parametrize(('question', 'excerpt', 'supported'), [
    (PATTERN, COMPUTER, False), (PROCESS, COMPUTER, False), (JUDGE, COMPUTER, False),
    (PATTERN, '커리어넷 식품공학과 자료는 그중 식품 성분 분석의 배경을 제공한다.', False),
    (PATTERN, '학사과정 과목 개요는 망원경 활용과 관측 자료 분석을 학습한다고 설명한다.', True),
    (PATTERN, '환경과학과는 인간 활동과 환경 변화의 관계를 살피고 관측 자료로 환경 문제를 분석한다.', True),
    (PATTERN, '연세대 응용통계학과는 회귀분석을 실제 자료에 적용하는 교과 사례를 제공한다.', True),
    (JUDGE, '회계학과는 거래를 기록하고 의사결정에 필요한 회계정보를 정리하는 방법을 배운다.', True),
    (CAUSE, '경제 관련 글을 읽는 활동도 탐색 방법으로 소개한다.', False),
    (CAUSE, '커리어넷 사학과 자료는 역사 자료로 과거를 해석하는 접근을 소개한다.', True),
    (CAUSE, '사학과는 과거의 사건과 사회를 자료에서 살펴보고 그 의미를 탐구한다.', True),
    (CAUSE, '데이터사이언스학과는 통계학, 컴퓨터과학 등 여러 분야를 이용해 데이터에서 질문을 만들고 답을 찾는 방법을 배운다.', True),
    (CAUSE, '교육학과에서는 학습과 수업을 둘러싼 이론을 공부하고 교육 현장의 문제를 분석한다.', True),
    (MEDIA, '한서대 영상애니메이션학과는 스토리보드에서 대본을 화면과 시간으로 시각화하는 과정을 안내한다.', False),
    (MEDIA, '방사선학과는 의학 영상과 방사선 관련 기술, 영상정보의 처리를 공부한다.', False),
    (MEDIA, '미디어를 통해 정보가 전달되고 사회에서 해석되는 방식을 살펴본다.', True),
])
def test_response_needs_evidence_of_the_same_activity(question, excerpt, supported):
    assert supports_response(question, excerpt) is supported


@pytest.mark.parametrize(('candidate', 'source', 'topic'), [
    ('관찰한 결과 비교하는 활동', '관찰한 결과를 비교해 차이나 원인을 찾아보는 활동에 끌린다.', '관찰한 결과'),
    ('생명 현상과 건강 문제의 원인을 탐구', '생명 현상과 건강 문제의 원인을 살펴보고 싶다.', '생명 현상과 건강 문제의 원인'),
    ('전자 회로 설계', '전자 회로, 신호를 다룬다.', '전자 회로'),
])
def test_topic_is_not_cut_in_the_middle_of_a_word(candidate, source, topic):
    assert trimmed_topic(candidate, source) == topic


def test_disclaimers_are_not_offered_as_major_evidence():
    facts = [{'referenceKey': REF, 'text': COMPUTER + ' 이는 학과를 알아보기 위한 사례이며, 개인의 적성이나 입학 요건을 판정하는 기준은 아니다.'}]
    assert [excerpt for _, excerpt in explanation_excerpts(facts).values()] == [COMPUTER]


@pytest.mark.parametrize('claim', ['구성 요소를 살펴보는 학습이 적합할 것입니다.', '이 과정은 데이터를 다루는 데 필수적인 요소입니다.',
                                   '이 과목은 환경 변화를 이해하는 것을 중시하고 있어요.', '바다의 생태계를 탐구하는 데 중점을 둡니다.', '이 전공의 학습 내용과 잘 어울립니다.'])
def test_explanation_rejects_fit_or_importance_claims_absent_from_evidence(claim):
    quote = '생명과학과는 생명체의 구성과 기능을 탐구한다.'
    reason = '생명 현상에 보인 관심은 생명체의 구성과 기능을 살펴볼 단서예요. '
    assert explains_grounded_connection(reason + '생명체를 여러 수준에서 들여다보는 점이 연결돼요.', '생명 현상', '생명체의 구성과 기능', quote)
    assert not explains_grounded_connection(reason + claim, '생명 현상', '생명체의 구성과 기능', quote)


@pytest.mark.parametrize(('response_topic', 'succeeds'), [
    ('생명 현상과 건강 문제의 원인 탐구', True),
    ('생명체의 건강을 지키는 활동', False),
    ('원인 탐구', False),
])
def test_response_topic_may_only_lose_trailing_words(settings, response_topic, succeeds):
    value, facts, chat = explanation_with(
        '생명 현상과 건강 문제의 원인을 탐구하는 활동에 관심이 있다.', LIFE,
        responseTopic=response_topic, majorTopic='생명체의 구성과 기능',
        reason='생명 현상과 건강 문제의 원인 탐구에 보인 관심은 생명체의 구성과 기능을 살펴볼 단서예요. 생명체를 여러 수준에서 들여다보는 점이 연결돼요.')
    if succeeds:
        connection = ExplorationModel(chat).generate(value, facts)['result']['connections'][0]['connection']
        assert connection.startswith('생명 현상과 건강 문제의 원인 탐구에 보인 관심은')
    else:
        with pytest.raises(ModelFault):
            ExplorationModel(chat).generate(value, facts)


@pytest.mark.parametrize('major_topic', [
    '운영 전략을 세우는 방법', '예산 편성과 인사 관리', '조직 문화', '사람을 뽑고 배치하는 관리 방법',
])
def test_explanation_still_rejects_topics_absent_from_the_quote(settings, major_topic):
    value, facts, chat = explanation_with(
        '조직이 운영되는 과정이 흥미롭다.', BUSINESS, responseTopic='조직이 운영되는 과정', majorTopic=major_topic,
        reason=f'조직이 운영되는 과정에 보인 흥미는 {major_topic}을 살펴볼 단서예요. 일이 이루어지는 흐름을 따라가는 점이 연결돼요.')
    with pytest.raises(ModelFault) as error:
        ExplorationModel(chat).generate(value, facts)
    assert error.value.output_issue == ExplanationOutputIssue.GROUNDING


def test_restating_a_media_question_is_allowed_but_claiming_media_use_is_not(settings):
    question = '글·이미지·영상·움직임으로 생각을 표현하는 활동에 끌린다.'
    text = '전공에서는 광고 문구를 제작하는 실습을 안내한다.'
    draft = dict(responseTopic='글·이미지·영상·움직임으로 생각을 표현하는 활동', majorTopic='광고 문구를 제작하는 실습')
    lead = '글·이미지·영상·움직임으로 생각을 표현하는 활동에 끌린 것은 광고 문구를 제작하는 실습을 살펴볼 단서예요. '
    value, facts, chat = explanation_with(question, text, **draft, reason=lead + '생각을 문구로 다듬어 전하는 점이 연결돼요.')
    assert ExplorationModel(chat).generate(value, facts)['status'] == 'SUCCEEDED'

    for claim in ('다양한 매체를 활용해 생각을 전하는 점이 연결돼요.', '다양한 형식의 콘텐츠로 생각을 나누는 점이 연결돼요.'):
        value, facts, chat = explanation_with(question, text, **draft, reason=lead + claim)
        with pytest.raises(ModelFault):
            ExplorationModel(chat).generate(value, facts)


@pytest.mark.parametrize(('text', 'succeeds'), [
    ('중앙대 문예창작전공도 창작품 합평과 토론을 안내한다.', False),
    ('신문방송학과는 창작품 합평과 토론을 거쳐 방송을 만드는 방법을 배운다.', True),
])
def test_media_influence_answer_needs_media_evidence(settings, text, succeeds):
    value, facts, chat = explanation_with(
        '미디어가 사람과 사회에 영향을 주는 방식을 배우고 싶다.', text,
        responseTopic='미디어가 사람과 사회에 영향을 주는 방식', majorTopic='창작품 합평과 토론',
        reason='미디어가 사람과 사회에 영향을 주는 방식을 배우고 싶다는 관심은 창작품 합평과 토론을 살펴볼 단서예요. 서로의 해석을 나누는 점이 연결돼요.')
    if succeeds:
        assert ExplorationModel(chat).generate(value, facts)['status'] == 'SUCCEEDED'
    else:
        assert ExplorationModel(chat).generate(value, facts)['status'] == 'DECLINED'
        assert chat.calls == 0


@pytest.mark.parametrize('claim', ['전공의 핵심과 잘 이어져요.', '이 전공과 잘 맞아떨어져요.', '일의 흐름을 아는 것이 중요하다는 점이 같아요.'])
def test_explanation_rejects_unsupported_fit_and_importance_claims(settings, claim):
    value, facts, chat = explanation_with(
        '조직이 운영되는 과정이 흥미롭다.', BUSINESS, responseTopic='조직이 운영되는 과정', majorTopic='운영 방식을 관리하는 방법',
        reason=f'조직이 운영되는 과정에 보인 흥미는 운영 방식을 관리하는 방법을 살펴볼 단서예요. 일의 흐름을 살피는 관심이 {claim}')
    with pytest.raises(ModelFault):
        ExplorationModel(chat).generate(value, facts)


def test_sentences_describing_learning_are_offered_before_course_names(settings):
    value, facts = explanation_bound()
    facts[0]['text'] = '광운대 로봇학부에는 센서공학이 있다. 로봇을 설계하고 제어하는 방법을 공부한다.'
    chat = FakeChat({'targetName': 'TEST', 'connections': []})
    ExplorationModel(chat).generate(value, facts)
    assert [item['text'] for item in json.loads(chat.messages[1].content)['evidence']] == [
        '로봇을 설계하고 제어하는 방법을 공부한다.', '광운대 로봇학부에는 센서공학이 있다.',
    ]


@pytest.mark.parametrize('with_description', [True, False])
def test_course_name_evidence_is_used_only_without_described_learning(settings, with_description):
    names = '커리어넷은 세포생물학, 유전학과 일반생태학을 소개한다.'
    value, facts = explanation_bound()
    signal = value['input']['signals'][0]
    signal.update(questionText='자연 현상이 일어나는 원리를 관찰하는 것이 궁금하다.', answerText='매우 그렇다')
    value['input']['signals'].append(dict(signal, questionId='ACT-EXP-01', kind='INTEREST',
                                          questionText='생명 현상의 원인을 탐구하는 활동에 관심이 있다.'))
    value['inputHash'] = input_hash(value)
    facts[0]['text'] = f'{LIFE} {names}' if with_description else names
    named = {'answerChoice': 'A1', 'evidenceChoice': 'E2' if with_description else 'E1',
             'responseTopic': '자연 현상이 일어나는 원리', 'majorTopic': '유전학과 일반생태학',
             'reason': '자연 현상이 일어나는 원리에 보인 관심은 유전학과 일반생태학을 살펴볼 단서예요. 현상의 까닭을 궁금해하는 점이 연결돼요.'}
    described = {'answerChoice': 'A2', 'evidenceChoice': 'E1', 'responseTopic': '생명 현상의 원인',
                 'majorTopic': '생명체의 구성과 기능',
                 'reason': '생명 현상의 원인에 보인 관심은 생명체의 구성과 기능을 살펴볼 단서예요. 생명체를 여러 수준에서 들여다보는 점이 연결돼요.'}
    chat = FakeChat({'targetName': 'TEST', 'connections': [named, described] if with_description else [named]})
    connections = ExplorationModel(chat).generate(value, facts)['result']['connections']
    assert [item['questionId'] for item in connections] == (['ACT-EXP-01'] if with_description else ['ACT-DES-01'])


CONTENT_QUESTION = '정보를 글·영상·이미지 같은 콘텐츠로 전달하는 활동에 관심이 있다.'
PATTERN_QUESTION = '숫자나 자료를 비교해 패턴을 찾는 활동이 흥미롭다.'
GROWTH_QUESTION = '사람이 배우고 성장하는 과정을 공부하는 데 관심이 있다.'


@pytest.mark.parametrize(('question', 'weak_evidence'), [
    (CONTENT_QUESTION, '중앙대 문예창작전공도 창작품 합평과 토론을 안내한다.'),
    (PATTERN_QUESTION, '로봇을 설계하고 제어하는 방법을 공부한다. 광운대 로봇학부에는 센서공학이 있다.'),
    (GROWTH_QUESTION, '커리어넷은 국사교육론, 역사학개론과 서양사를 소개한다.'),
])
def test_weak_connections_are_excluded_before_model_invocation(settings, question, weak_evidence):
    value, facts = explanation_bound()
    value['input']['signals'][0]['questionText'] = question
    value['inputHash'] = input_hash(value)
    facts[0]['text'] = weak_evidence
    chat = FakeChat(None)
    assert ExplorationModel(chat).generate(value, facts)['status'] == 'DECLINED'
    assert chat.calls == 0


@pytest.mark.parametrize(('qualifier', 'expected'), [
    ('그중 글로 생각을 표현하는 데 관심이 있다면', 'SUCCEEDED'),
    ('콘텐츠를 전달하는 활동에 흥미를 느꼈으므로', 'DECLINED'),
])
def test_content_answer_only_supports_a_conditional_link_to_writing(settings, qualifier, expected):
    value, facts, chat = explanation_with(
        CONTENT_QUESTION, '문예창작학과는 문학 이론을 읽고 시·소설 등의 창작과 표현을 연습한다.',
        responseTopic='콘텐츠로 전달하는 활동', majorTopic='창작과 표현',
        reason=('콘텐츠로 전달하는 활동에 보인 흥미는 전공의 창작과 표현을 살펴볼 단서예요. '
                + qualifier + ' 생각을 문장으로 전달하는 과정을 살펴볼 수 있어요.'))
    assert ExplorationModel(chat).generate(value, facts)['status'] == expected
    assert chat.calls == 1


@pytest.mark.parametrize(('question', 'weak_evidence', 'strong_evidence', 'response', 'topic', 'reason'), [
    (PATTERN_QUESTION, '로봇을 설계하고 제어하는 방법을 공부한다.',
     '학생은 여러 측정값을 비교해 센서 데이터의 패턴을 분석하는 방법을 배운다.',
     '자료를 비교해 패턴을 찾는 활동', '센서 데이터의 패턴을 분석하는 방법',
     '자료를 비교해 패턴을 찾는 활동에 대한 흥미는 센서 데이터의 패턴을 분석하는 방법을 살펴볼 단서예요. 여러 값을 나란히 비교해 반복되는 특징을 찾는 점이 이어져요.'),
    (GROWTH_QUESTION, '커리어넷은 국사교육론, 역사학개론과 서양사를 소개한다.',
     '서울대 과목 개요에는 역사 수업 설계·평가와 교재를 다루는 사례가 있다.',
     '사람이 배우고 성장하는 과정', '역사 수업 설계·평가',
     '사람이 배우고 성장하는 과정에 대한 관심은 역사 수업 설계·평가를 살펴볼 단서예요. 학습 내용을 어떻게 가르치고 이해했는지 확인하는 점이 이어져요.'),
])
def test_named_signal_can_use_substantive_evidence_from_the_same_source(settings, question, weak_evidence, strong_evidence, response, topic, reason):
    from app.exploration_model import explanation_excerpts
    value, facts, chat = explanation_with(question, f'{weak_evidence} {strong_evidence}',
        responseTopic=response, majorTopic=topic, reason=reason)
    weak_choice = next(choice for choice, (_, quote) in explanation_excerpts(facts).items() if quote == weak_evidence)
    strong_choice = next(choice for choice, (_, quote) in explanation_excerpts(facts).items() if quote == strong_evidence)
    row = chat.result['connections'][0]
    row['evidenceChoice'] = strong_choice
    result = ExplorationModel(chat).generate(value, facts)
    assert result['status'] == 'SUCCEEDED'
    assert json.loads(chat.messages[1].content)['input']['signals'][0]['allowedEvidenceChoices'] == [strong_choice]
    row['evidenceChoice'] = weak_choice
    assert ExplorationModel(chat).generate(value, facts)['status'] == 'DECLINED'


SOFTWARE_COURSE_LIST = (
    '숭실대 소프트웨어학부의 교과에는 소프트웨어공학, 소프트웨어분석및설계와 소프트웨어프로젝트가 있다.'
)
SOFTWARE_LEARNING = '성균관대 소프트웨어학과 로드맵은 실생활 문제를 알고리즘으로 해결하는 교과를 소개한다.'


@pytest.mark.parametrize('only_course_titles', [True, False])
@pytest.mark.parametrize('course_list', [SOFTWARE_COURSE_LIST,
    '커리어넷은 문학과 언어, 현대 소설론을 사례로 든다.',
    '안내에서 1학년에 소프트웨어프로젝트 수업이 개설된다.',
    '커리어넷은 정책학, 지방행정과 공공관리론을 대표 과목으로 든다.',
])
def test_course_titles_are_not_used_as_evidence_of_learning_activities(settings, only_course_titles, course_list):
    value, facts = explanation_bound()
    facts[0]['text'] = course_list
    if not only_course_titles:
        facts[0]['text'] += ' ' + SOFTWARE_LEARNING
    chat = FakeChat({'targetName': 'TEST', 'connections': []})
    result = ExplorationModel(chat).generate(value, facts)
    assert result['status'] == 'DECLINED'
    assert chat.calls == (0 if only_course_titles else 1)
    if not only_course_titles:
        assert json.loads(chat.messages[1].content)['evidence'] == [
            {'evidenceChoice': 'E1', 'text': SOFTWARE_LEARNING}
        ]


def test_strongest_connection_is_followed_by_a_different_kind_when_supported(settings):
    value, facts = explanation_bound()
    signal = value['input']['signals'][0]
    value['input']['signals'] += [
        dict(signal, questionId='ACT-DES-02', questionText='모형을 제작하는 활동이 재미있나요?'),
        dict(signal, kind='LEARNING', metricCode='LEARN_DES', questionId='LEARN-DES-01',
             questionText='재료 실험을 하는 방법을 배우고 싶나요?'),
    ]
    value['inputHash'] = input_hash(value)
    facts[0]['text'] = '직접 설계하는 활동을 진행한다. 종이 모형을 제작한다. 여러 재료를 대상으로 재료 실험을 한다.'
    chat = FakeChat({'targetName': 'TEST', 'connections': [
        {'answerChoice': 'A1', 'evidenceChoice': 'E1', 'responseTopic': '직접 설계하는 활동',
         'majorTopic': '설계하는 활동',
         'reason': '직접 설계하는 활동에 관심을 보인 점은 이 전공을 살펴볼 단서예요. 원하는 구조를 생각해 설계한다는 공통점이 있어요.'},
        {'answerChoice': 'A2', 'evidenceChoice': 'E2', 'responseTopic': '모형을 제작하는 활동',
         'majorTopic': '종이 모형을 제작',
         'reason': '모형을 제작하는 활동에 보인 흥미는 종이 모형을 제작하는 일과 이어져요. 계획한 모양을 실제 형태로 만들어 보는 일이 공통점이에요.'},
        {'answerChoice': 'A3', 'evidenceChoice': 'E3', 'responseTopic': '재료 실험을 하는 방법',
         'majorTopic': '재료 실험을 한다',
         'reason': '재료 실험을 하는 방법을 배우고 싶다면 어떤 재료 실험을 한다고 소개하는지 살펴볼 수 있어요. 여러 재료를 직접 실험하면서 차이를 살펴본다는 점이 이어져요.'},
    ]})
    rows = ExplorationModel(chat).generate(value, facts)['result']['connections']
    assert [row['questionId'] for row in rows] == ['ACT-DES-01', 'LEARN-DES-01', 'ACT-DES-02']
    assert len(chat.schema.model_validate(chat.result).connections) == 3

    chat.result['connections'].pop()
    rows = ExplorationModel(chat).generate(value, facts)['result']['connections']
    assert [row['questionId'] for row in rows] == ['ACT-DES-01', 'ACT-DES-02']


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
