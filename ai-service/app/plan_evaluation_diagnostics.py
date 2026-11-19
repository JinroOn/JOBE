"""Bounded, value-free diagnostics for the operator's plan evaluation report."""
from json import JSONDecodeError

from langchain_core.exceptions import OutputParserException
from openai import ContentFilterFinishReasonError, LengthFinishReasonError
from pydantic import ValidationError

from app.exploration_contract import ContractViolation
from app.exploration_plan_draft import EvidenceTask, FictionalChoiceTask, PlanWeekDraft


ERROR_TYPES = frozenset({
    'missing', 'extra_forbidden', 'string_type', 'string_too_short', 'string_too_long',
    'string_pattern_mismatch', 'literal_error', 'int_type', 'greater_than_equal',
    'less_than_equal', 'list_type', 'too_short', 'too_long', 'model_type', 'value_error',
    'json_invalid', 'duplicate_options',
})
FINISH_REASONS = frozenset({'stop', 'tool_calls', 'function_call', 'length', 'content_filter'})
WEEK_FIELDS = frozenset(PlanWeekDraft.model_fields) | {'tasks'}
TASK_FIELDS = {
    EvidenceTask.__name__: frozenset(EvidenceTask.model_fields),
    FictionalChoiceTask.__name__: frozenset(FictionalChoiceTask.model_fields),
}


def field_path(location):
    if not location or location[0] not in {'targetName', 'weeks'}:
        return []
    path = [location[0]]
    if path[0] == 'weeks' and len(location) > 1:
        index = location[1]
        if type(index) is int and 0 <= index < 12:
            path.append(index)
            if len(location) > 2 and location[2] in WEEK_FIELDS:
                path.append(location[2])
                if location[2] == 'task' and len(location) > 3 and location[3] in TASK_FIELDS:
                    branch = location[3]
                    path.append(branch)
                    if len(location) > 4 and location[4] in TASK_FIELDS[branch]:
                        path.append(location[4])
    return path


def output_failure(stage, error=None, raw=None):
    result = {'stage': stage, 'kind': 'NO_PARSED_OUTPUT'}
    metadata = getattr(raw, 'response_metadata', {})
    extra = getattr(raw, 'additional_kwargs', {})
    finish = metadata.get('finish_reason') if isinstance(metadata, dict) else None
    if isinstance(finish, str) and finish in FINISH_REASONS:
        result['finishReason'] = finish

    if finish == 'length' or isinstance(error, LengthFinishReasonError):
        result['kind'] = 'OUTPUT_LENGTH_LIMIT'
    elif finish == 'content_filter' or isinstance(error, ContentFilterFinishReasonError):
        result['kind'] = 'CONTENT_FILTER'
    elif isinstance(extra, dict) and extra.get('refusal'):
        result['kind'] = 'PROVIDER_REFUSAL'
    else:
        for _ in range(4):
            if isinstance(error, ValidationError):
                result['kind'] = 'SCHEMA_VALIDATION'
                result['errorCount'] = error.error_count()
                result['errors'] = [
                    {'type': entry['type'] if entry['type'] in ERROR_TYPES else 'other',
                     'path': field_path(entry['loc'])}
                    for entry in error.errors(include_input=False, include_context=False, include_url=False)[:8]
                ]
                break
            if isinstance(error, JSONDecodeError):
                result['kind'] = 'INVALID_JSON'
                break
            if isinstance(error, OutputParserException):
                result['kind'] = 'OUTPUT_PARSER_ERROR'
            elif isinstance(error, ContractViolation):
                result['kind'] = 'CONTRACT_VALIDATION'
                break
            error = getattr(error, '__cause__', None)
            if error is None:
                break
    return result
