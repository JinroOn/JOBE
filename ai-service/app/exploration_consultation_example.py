"""Provider draft for a request to name one learning example from the supplied material."""
import re

from app.exploration_contract import ContractViolation, Reference, StrictModel, text


SINGLE_LEARNING_EXAMPLE = re.compile(
    r'(?:학습|수업|교과목)\s*(?:예시|사례).{0,12}(?:하나|한\s*가지)\s*만'
    r'|(?:하나|한\s*가지)\s*의?\s*(?:학습|수업|교과목)\s*(?:예시|사례)'
    r'|\b(?:exactly\s+one|just\s+one|a\s+single)\s+(?:learning|course)\s+example\b',
    re.IGNORECASE,
)
LIST_MARKERS = re.compile(r'[,，、·;；/「」\n\r]|(?:와|과)\s|\s(?:및|또는|그리고|and|or)\s', re.IGNORECASE)


class SingleExampleDraft(StrictModel):
    example: text(80) | None
    referenceKey: Reference | None


SINGLE_EXAMPLE_INSTRUCTIONS = (
    ' For this request, return example and referenceKey only. Copy the exact name of just one concrete learning example '
    'or one course title from the evidence, as a single short span. Do not include the surrounding sentence, any list '
    'or other course title. Use null for both fields if the supplied evidence lacks a suitable example.'
)


def render_single_example(value, facts: list[dict]) -> dict:
    draft = SingleExampleDraft.model_validate(value)
    if draft.example is None and draft.referenceKey is None:
        return {'summary': '제공된 자료에서 요청하신 학습 예시를 확인하기 어려워요.',
                'questions': [], 'referenceKeys': [fact['referenceKey'] for fact in facts]}
    fact = next((fact for fact in facts if fact['referenceKey'] == draft.referenceKey), None)
    if fact is None or not draft.example or LIST_MARKERS.search(draft.example) or draft.example not in fact['text']:
        raise ContractViolation('Learning example must be a single span from its approved reference')
    return {'summary': f'제공된 자료에서 확인한 학습 예시입니다. 「{draft.example}」',
            'questions': [], 'referenceKeys': [draft.referenceKey]}
