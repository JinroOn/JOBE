"""Provider-only plan draft; public and historical Plan12Result stays unchanged."""
import re
from typing import Annotated, Literal

from pydantic import Field, ValidationInfo, field_validator, model_validator
from pydantic_core import PydanticCustomError

from app.exploration_contract import ContractViolation, Plan12Result, StrictModel, text


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


class FictionalTask(StrictModel):
    inputKind: Literal['FICTIONAL_EXAMPLE']
    inputText: text(70)
    activity: Literal['CHOOSE_OPTION', 'CLASSIFY_ITEMS', 'OBSERVE_DIFFERENCE', 'ORDER_STEPS', 'SKETCH_LABELS']
    optionA: text(30)
    optionB: text(30)

    @field_validator('optionB')
    @classmethod
    def distinct_options(cls, option, info: ValidationInfo):
        first = info.data.get('optionA')
        if first is not None and ' '.join(first.split()).casefold() == ' '.join(option.split()).casefold():
            raise PydanticCustomError('duplicate_options', 'The two entries must be distinct')
        return option


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
        activities = {week.task.activity for week in self.weeks[4:]}
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
    weeks = []
    for week in draft.weeks:
        task = week.task
        if isinstance(task, EvidenceTask):
            if task.excerptIndex > len(candidates):
                raise ContractViolation('Plan draft excerpt index is not available')
            input_text = candidates[task.excerptIndex - 1]
            provided_input = f'근거 발췌: 「{input_text}」'
            activity = task.activity
        else:
            input_text = ' '.join(task.inputText.split())
            first, second = (' '.join(option.split()) for option in (task.optionA, task.optionB))
            provided_input = f'가상 예시: 「{input_text}」 (A 「{first}」 / B 「{second}」)'
            activity = task.activity
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
    'For FICTIONAL_EXAMPLE, choose activity and supply an imaginary situation in inputText (up to 70 characters) '
    'plus concrete, distinct entries in optionA and optionB (up to 30 characters each). '
    'Weeks 5-12 must include CLASSIFY_ITEMS, OBSERVE_DIFFERENCE, ORDER_STEPS and SKETCH_LABELS at least once each. '
    'For CLASSIFY_ITEMS, give two named categories and their rule in inputText, and two items as A and B. '
    'Example: "도서 안내를 반납일 정보 칸과 책 이름 칸으로 나눈다", A="금요일까지", B="숲 속 이야기". '
    'For OBSERVE_DIFFERENCE, give a situation and two short displays, notes or records with a visible difference. '
    'For ORDER_STEPS, give a goal and ordering clue, with two actions to put in order. '
    'For SKETCH_LABELS, give a simple screen purpose and two concrete button or box names. '
    'For CHOOSE_OPTION, give a situation with a goal or preference and two choices the reader can compare. '
    'Use everyday Korean and put all rules, clues and examples the reader needs in these fields. '
    'Use varied topics present in the approved evidence. The server assigns the reference for every week. '
    'Use different excerpts for information and questions, and vary fictional actions across the last two phases. '
    'Suggest a small estimatedMinutes value between 1 and 20. Each task uses paper and a pen. '
    'Keep all input and options self-contained. Never invent a URL or require an article, lesson, device or partner. '
    'Keep fictional examples separate from factual claims and avoid admissions, aptitude, forecasts and rankings.'
)
