"""Provider-only plan draft; public and historical Plan12Result stays unchanged."""
import re
from typing import Annotated, Literal

from pydantic import Field, model_validator
from pydantic_core import PydanticCustomError

from app.exploration_contract import ContractViolation, Plan12Result, StrictModel, text
from app.exploration_plan_scenarios import FICTIONAL_SCENARIOS


MAX_EXCERPT_CANDIDATES = 12
MAX_EXCERPT_LENGTH = 120
SENTENCE_ENDINGS = '.!?。！？'
SENTENCE_BOUNDARY = re.compile(r'(?<=[.!?。！？])\s+')


def excerpt_candidates(text: str) -> list[str]:
    """Collect bounded, visibly sentence-ended spans without shortening a span."""
    sentences = SENTENCE_BOUNDARY.split(' '.join(text.split()))
    candidates = dict.fromkeys(sentence for sentence in sentences
                               if 0 < len(sentence) <= MAX_EXCERPT_LENGTH
                               and sentence[-1] in SENTENCE_ENDINGS
                               and not sentence.endswith('...'))
    return list(candidates)[:MAX_EXCERPT_CANDIDATES]


class EvidenceTask(StrictModel):
    inputKind: Literal['EVIDENCE_EXCERPT']
    excerptIndex: Annotated[int, Field(ge=1, le=MAX_EXCERPT_CANDIDATES)]
    activity: Literal['MARK_KEYWORD', 'WRITE_QUESTION']


VARIED_ACTIVITIES = frozenset({'CLASSIFY_ITEMS', 'OBSERVE_DIFFERENCE', 'ORDER_STEPS', 'SKETCH_LABELS'})
INFO_ACTIVITIES = ('MARK_KEYWORD', 'PARAPHRASE_EXCERPT', 'NOTE_INFORMATION_GAP', 'WRITE_QUESTION')


class FictionalTask(StrictModel):
    inputKind: Literal['FICTIONAL_EXAMPLE']
    scenarioIndex: Annotated[int, Field(ge=1, le=len(FICTIONAL_SCENARIOS))]


class PlanWeekDraft(StrictModel):
    week: Annotated[int, Field(ge=1, le=12)]
    phase: Literal['INFO', 'BASIC_LEARNING', 'SMALL_ACTIVITY']
    estimatedMinutes: Annotated[int, Field(ge=1, le=20)]
    task: EvidenceTask | FictionalTask


class Plan12Draft(StrictModel):
    targetName: text(100)
    weeks: Annotated[list[PlanWeekDraft], Field(min_length=12, max_length=12)]

    @model_validator(mode='after')
    def ordered_phases_and_varied_activities(self):
        for number, week in enumerate(self.weeks, 1):
            phase = 'INFO' if number <= 4 else 'BASIC_LEARNING' if number <= 8 else 'SMALL_ACTIVITY'
            if week.week != number or week.phase != phase:
                raise ValueError('Twelve ordered weeks and phases are required')
        indices = [week.task.scenarioIndex for week in self.weeks if isinstance(week.task, FictionalTask)]
        if len(indices) != len(set(indices)):
            raise PydanticCustomError('repeated_scenario', 'Each fictional scenario can be used only once')
        activities = {FICTIONAL_SCENARIOS[week.task.scenarioIndex - 1].activity
                      for week in self.weeks[4:] if isinstance(week.task, FictionalTask)}
        if not VARIED_ACTIVITIES.issubset(activities):
            raise PydanticCustomError('missing_activity_variety', 'Weeks 5-12 must include classification, observation, ordering and sketching')
        return self


ACTIVITY_TEXT = {
    'MARK_KEYWORD': (
        '낯선 말 표시하기',
        '발췌에서 낯설거나 눈에 띄는 말 하나에 표시하고 고른 이유를 한 문장으로 적기',
        '표시한 말 하나와 고른 이유 한 문장',
        '발췌에서 눈에 띄는 말 하나만 옮겨 적기',
        '옮겨 적은 말 하나',
    ),
    'WRITE_QUESTION': (
        '탐색 질문 만들기',
        '발췌에서 말 하나를 골라 더 알고 싶은 점을 질문 한 문장으로 적기',
        '고른 말 하나와 질문 한 문장',
        '발췌에서 질문해 보고 싶은 말 하나만 표시하기',
        '표시한 말 하나',
    ),
    'PARAPHRASE_EXCERPT': (
        '발췌를 내 말로 바꾸기',
        '발췌에서 직접 알 수 있는 내용을 내 말로 한 문장에 옮겨 적기',
        '내 말로 옮긴 한 문장',
        '발췌에서 중요한 짧은 구절 하나를 골라 옮겨 적기',
        '옮겨 적은 구절 하나',
    ),
    'NOTE_INFORMATION_GAP': (
        '확인한 내용과 더 볼 내용 나누기',
        '종이를 두 칸으로 나누고 발췌에서 확인한 내용과 아직 확인이 필요한 내용을 하나씩 적기',
        '확인한 내용과 더 확인할 내용을 정리한 두 칸',
        '발췌만으로 알기 어려운 점 하나를 낱말로 적기',
        '더 확인할 낱말 하나',
    ),
    'CHOOSE_OPTION': (
        '두 선택지 살펴보기',
        '가상 상황에서 먼저 살펴볼 선택지를 하나 고르고 고른 이유를 한 문장으로 적기',
        '고른 선택지 하나와 고른 이유 한 문장',
        '제시된 선택지 A와 B 중 하나의 글자만 적기',
        'A 또는 B 한 글자',
    ),
    'CLASSIFY_ITEMS': (
        '두 칸으로 나누기',
        '가상 설명에 나온 두 칸을 그린 뒤 기준에 맞춰 A와 B를 적기',
        'A와 B를 나눠 적은 두 칸',
        'A가 들어갈 칸의 이름만 적기',
        'A가 들어갈 칸의 이름 하나',
    ),
    'OBSERVE_DIFFERENCE': (
        '차이 한 가지 찾기',
        'A와 B를 읽고 어디가 다른지 한 줄로 적기',
        'A와 B의 차이를 적은 한 줄',
        '달라진 낱말이나 표시 하나만 옮겨 적기',
        '옮겨 적은 낱말이나 표시 하나',
    ),
    'ORDER_STEPS': (
        '두 행동에 순서 붙이기',
        '가상 설명의 단서를 따라 A와 B에 1번과 2번 붙이기',
        '순서 번호가 붙은 행동 두 개',
        '먼저 할 행동 옆에 1번만 붙이기',
        '1번을 붙인 행동 하나',
    ),
    'SKETCH_LABELS': (
        '작은 화면 그리기',
        '종이에 네모 두 개를 그리고 A와 B의 이름을 하나씩 쓰기',
        '이름을 적은 네모 두 개',
        '네모 하나만 그리고 A의 이름을 쓰기',
        'A의 이름을 적은 네모 하나',
    ),
}


def render_plan_draft(value, facts) -> dict:
    """Resolve excerpt indices against the same approved candidates shown to the model."""
    if len(facts) != 1:
        raise ContractViolation('Plan draft requires one bound evidence item')
    fact = facts[0]
    reference_key = fact['referenceKey']
    candidates = excerpt_candidates(fact['text'])
    draft = Plan12Draft.model_validate(value)
    unused_info_indices = dict.fromkeys(range(1, len(candidates) + 1))
    weeks = []
    for week in draft.weeks:
        task = week.task
        if isinstance(task, EvidenceTask):
            if task.excerptIndex > len(candidates):
                raise ContractViolation('Plan draft excerpt index is not available')
            index = task.excerptIndex
            activity = task.activity
            if week.phase == 'INFO':
                activity = INFO_ACTIVITIES[week.week - 1]
                if unused_info_indices and index not in unused_info_indices:
                    index = next(iter(unused_info_indices))
                unused_info_indices.pop(index, None)
            input_text = candidates[index - 1]
            provided_input = f'근거 발췌: 「{input_text}」'
        else:
            scenario = FICTIONAL_SCENARIOS[task.scenarioIndex - 1]
            provided_input = f'가상 예시: 「{scenario.situation}」 (A 「{scenario.first}」 / B 「{scenario.second}」)'
            activity = scenario.activity
        title, action, output, easier_action, easier_output = ACTIVITY_TEXT[activity]
        weeks.append({
            'week': week.week, 'phase': week.phase, 'title': title,
            'tasks': [
                f'준비: 종이와 펜 | 시간: 약 {week.estimatedMinutes}분, 부담되면 줄이기 | 방식: 혼자',
                f'탐색 제안: {provided_input} | 행동: {action}. 쉬운 대안: {easier_action} (결과: {easier_output}).',
            ],
            'expectedOutput': output, 'referenceKeys': [reference_key],
        })
    return Plan12Result.model_validate({'targetName': draft.targetName, 'weeks': weeks}).model_dump()


PLAN12_INSTRUCTIONS = (
    'Produce exactly weeks 1-12: weeks 1-4 INFO, 5-8 BASIC_LEARNING, 9-12 SMALL_ACTIVITY. '
    'Return targetName and the Plan12Draft week fields only. The server writes titles, actions, alternatives and outputs. '
    'Fill each week\'s task with one of the two schema shapes. '
    'For EVIDENCE_EXCERPT, select the index of one supplied excerptCandidates entry as excerptIndex '
    'and choose MARK_KEYWORD or WRITE_QUESTION for activity. The server writes the excerpt. '
    'If excerptCandidates is empty, use FICTIONAL_EXAMPLE. Avoid reusing the same excerpt when suitable alternatives exist. '
    'For FICTIONAL_EXAMPLE, select the index of one supplied fictionalScenarios entry as scenarioIndex. '
    'Its activity, situation and two entries are written by the server. Do not repeat a scenario index. '
    'Weeks 5-12 must select at least one scenario of each activity: CLASSIFY_ITEMS, OBSERVE_DIFFERENCE, ORDER_STEPS, SKETCH_LABELS. '
    'For example, indices 2, 3, 4 and 5 satisfy that requirement when each is used once in weeks 5-12. '
    'Use the approved evidence for factual excerpts. The server assigns the reference for every week. '
    'Use different excerpts for information and questions, and choose fictional scenarios appropriate for practice in the last two phases. '
    'Suggest a small estimatedMinutes value between 1 and 20. Each task uses paper and a pen. '
    'Keep all input and options self-contained. Never invent a URL or require an article, lesson, device or partner. '
    'Keep fictional examples separate from factual claims and avoid admissions, aptitude, forecasts and rankings.'
)
