"""Provider-only plan draft; public and historical Plan12Result stays unchanged."""
from typing import Annotated, Literal

from pydantic import Field, model_validator

from app.exploration_contract import ContractViolation, Plan12Result, References, StrictModel, text


class PlanWeekDraft(StrictModel):
    week: Annotated[int, Field(ge=1, le=12)]
    phase: Literal['INFO', 'BASIC_LEARNING', 'SMALL_ACTIVITY']
    title: text(100)
    materials: text(35)
    estimatedMinutes: Annotated[int, Field(ge=1, le=20)]
    inputKind: Literal['EVIDENCE_EXCERPT', 'FICTIONAL_EXAMPLE']
    inputText: text(70)
    action: text(40)
    expectedOutput: text(50)
    easierAction: text(30)
    easierOutput: text(30)
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


def render_plan_draft(value, facts) -> dict:
    """Validate slots and literal excerpt provenance, not meaning or educational quality."""
    draft = Plan12Draft.model_validate(value)
    references = {fact['referenceKey']: ' '.join(fact['text'].split()) for fact in facts}
    weeks = []
    for week in draft.weeks:
        if any(ref not in references for ref in week.referenceKeys):
            raise ContractViolation('Invalid plan draft evidence')
        if week.inputKind == 'EVIDENCE_EXCERPT':
            excerpt = ' '.join(week.inputText.split())
            if not any(excerpt in references[ref] for ref in week.referenceKeys):
                raise ContractViolation('Invalid plan draft evidence')
        label = '근거 발췌' if week.inputKind == 'EVIDENCE_EXCERPT' else '가상 예시'
        weeks.append({
            'week': week.week, 'phase': week.phase, 'title': week.title,
            'tasks': [
                f'준비: {week.materials} | 시간: 약 {week.estimatedMinutes}분, 부담되면 줄이기 | 방식: 혼자',
                f'탐색 제안: {label}: {week.inputText} | 행동: {week.action}. '
                f'쉬운 대안: 같은 입력으로 {week.easierAction} (결과: {week.easierOutput}).',
            ],
            'expectedOutput': week.expectedOutput, 'referenceKeys': week.referenceKeys,
        })
    return Plan12Result.model_validate({'targetName': draft.targetName, 'weeks': weeks}).model_dump()


PLAN12_INSTRUCTIONS = (
    'Produce exactly weeks 1-12: weeks 1-4 INFO, 5-8 BASIC_LEARNING, 9-12 SMALL_ACTIVITY. '
    'Return the provider-only Plan12Draft fields, not public tasks strings. '
    'Every week needs materials, estimatedMinutes (1-20, a flexible suggestion), inputKind, inputText, '
    'action, expectedOutput, easierAction, easierOutput and referenceKeys. '
    'Students see the rendered task input and source titles/versions, not hidden evidence excerpts. '
    'For EVIDENCE_EXCERPT, inputText must be a short exact substring of one cited supplied fact; '
    'do not paraphrase it or quote a different reference. For FICTIONAL_EXAMPLE, provide tiny invented '
    'task material explicitly treated as fictional, never an approved fact or official activity. '
    'Cite only supplied referenceKey values and keep topics within the approved evidence. '
    'If evidence only names a course or topic, do not assume its principles, methods or features are known. '
    'Use the supplied names to sort familiar/unknown words or write one question instead. '
    'The action and easierAction must use inputText only; the easier action must reduce the work '
    'without another input, device, explanation or source. Make each output small and observable. '
    'Default to paper, a pen and a short solo action. Do not require a website, article, video, login, '
    'payment, installation, partner, prior lesson or university-level exercise. Never invent a URL. '
    'No admissions preparation, aptitude judgments, guaranteed outcomes, unsupported careers or rankings. '
    'Keep fields within their character limits; do not add public tasks, audit fields or identities. '
    'Example fictional task material: inputText="[책, 공책, 펜]", action="종이 물건/그 외 두 칸에 나누기", '
    'expectedOutput="물건 세 개를 나눈 표", easierAction="책 하나만 해당 칸에 적기", easierOutput="단어 한 개". '
    'Use an example relevant to the supplied topic; source membership alone never proves all actions are supported.'
)
