"""Provider-only plan draft; public and historical Plan12Result stays unchanged."""
from typing import Annotated, Literal

from pydantic import Field, ValidationInfo, field_validator, model_validator
from pydantic_core import PydanticCustomError

from app.exploration_contract import ContractViolation, Plan12Result, References, StrictModel, text


class EvidenceTask(StrictModel):
    inputKind: Literal['EVIDENCE_EXCERPT']
    inputText: text(70)
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
    referenceKeys: References


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
    """Validate slots and literal excerpt provenance, not meaning or educational quality."""
    draft = Plan12Draft.model_validate(value)
    references = {fact['referenceKey']: ' '.join(fact['text'].split()) for fact in facts}
    weeks = []
    for week in draft.weeks:
        if any(ref not in references for ref in week.referenceKeys):
            raise ContractViolation('Invalid plan draft evidence')
        task = week.task
        input_text = ' '.join(task.inputText.split())
        if isinstance(task, EvidenceTask):
            if not any(input_text in references[ref] for ref in week.referenceKeys):
                raise ContractViolation('Invalid plan draft evidence')
            provided_input = f'근거 발췌: 「{input_text}」'
            activity = task.activity
        else:
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
            'expectedOutput': output, 'referenceKeys': week.referenceKeys,
        })
    return Plan12Result.model_validate({'targetName': draft.targetName, 'weeks': weeks}).model_dump()


PLAN12_INSTRUCTIONS = (
    'Produce exactly weeks 1-12: weeks 1-4 INFO, 5-8 BASIC_LEARNING, 9-12 SMALL_ACTIVITY. '
    'Return targetName and the Plan12Draft week fields only. The server writes titles, actions, alternatives and outputs. '
    'Fill each week\'s task with one of the two schema shapes. '
    'For EVIDENCE_EXCERPT, copy an exact substring of up to 70 characters from a cited supplied fact '
    'into inputText and choose MARK_KEYWORD or WRITE_QUESTION for activity. '
    'For FICTIONAL_EXAMPLE, supply inputText as an imaginary situation of up to 70 characters '
    'and optionA and optionB as two distinct labels of up to 30 characters each. '
    'For example, inputText="동아리 소식을 정리하는 가상 상황", optionA="제목으로 분류", optionB="날짜 순서로 정리". '
    'Use varied topics actually present in the approved evidence and cite only supplied referenceKey values. '
    'Use names and excerpts for information and questions, and small fictional choices for activities when suitable. '
    'Suggest a small estimatedMinutes value between 1 and 20. Each task uses paper and a pen. '
    'Keep all input and options self-contained. Never invent a URL or require an article, lesson, device or partner. '
    'Keep fictional examples separate from factual claims and avoid admissions, aptitude, forecasts and rankings.'
)
