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


class FictionalChoiceTask(StrictModel):
    inputKind: Literal['FICTIONAL_EXAMPLE']
    inputText: text(70)
    optionA: text(30)
    optionB: text(30)

    @field_validator('optionB')
    @classmethod
    def distinct_options(cls, option, info: ValidationInfo):
        first = info.data.get('optionA')
        if first is not None and ' '.join(first.split()).casefold() == ' '.join(option.split()).casefold():
            raise PydanticCustomError('duplicate_options', 'The two options must be distinct')
        return option


class PlanWeekDraft(StrictModel):
    week: Annotated[int, Field(ge=1, le=12)]
    phase: Literal['INFO', 'BASIC_LEARNING', 'SMALL_ACTIVITY']
    estimatedMinutes: Annotated[int, Field(ge=1, le=20)]
    task: EvidenceTask | FictionalChoiceTask


class Plan12Draft(StrictModel):
    targetName: text(100)
    weeks: Annotated[list[PlanWeekDraft], Field(min_length=12, max_length=12)]

    @model_validator(mode='after')
    def ordered_phases(self):
        for number, week in enumerate(self.weeks, 1):
            phase = 'INFO' if number <= 4 else 'BASIC_LEARNING' if number <= 8 else 'SMALL_ACTIVITY'
            if week.week != number or week.phase != phase:
                raise ValueError('Twelve ordered weeks and phases are required')
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
            activity = 'CHOOSE_OPTION'
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
    'For FICTIONAL_EXAMPLE, supply inputText as an imaginary situation of up to 70 characters '
    'and optionA and optionB as two distinct labels of up to 30 characters each. '
    'For example, inputText="동아리 소식을 정리하는 가상 상황", optionA="제목으로 분류", optionB="날짜 순서로 정리". '
    'Use varied topics present in the approved evidence. The server assigns the reference for every week. '
    'Use names and excerpts for information and questions, and small fictional choices for activities when suitable. '
    'Suggest a small estimatedMinutes value between 1 and 20. Each task uses paper and a pen. '
    'Keep all input and options self-contained. Never invent a URL or require an article, lesson, device or partner. '
    'Keep fictional examples separate from factual claims and avoid admissions, aptitude, forecasts and rankings.'
)
