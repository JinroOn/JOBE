import asyncio
from copy import deepcopy
import json
from types import SimpleNamespace

import pytest

from scripts import evaluate_exploration_provider as evaluation


@pytest.fixture
def configured(monkeypatch):
    for name in ('OPENAI_API_KEY', 'FACTCHAT_API_KEY', 'EXPLORATION_LLM_MODEL', 'EXPLORATION_EVIDENCE_DATASET_VERSION',
                 'EXPLORATION_MODEL_EXECUTION_ENABLED', 'EXPLORATION_EVIDENCE_ENABLED'):
        monkeypatch.delenv(name, raising=False)
    for name, value in {'LLM_PROVIDER': 'openai', 'LLM_MODEL': 'TEST_MODEL', 'LLM_API_KEY': 'PRIVATE_TEST_SECRET',
                        'EXPLORATION_LLM_TIMEOUT_SECONDS': '1', 'MOCK_MODE': 'false'}.items():
        monkeypatch.setenv(name, value)


class ProtocolClient:
    def __init__(self, **settings):
        self.settings = settings
        self.root_client = SimpleNamespace(close=lambda: None)

    def with_structured_output(self, schema, *, include_raw):
        assert include_raw
        self.schema = schema
        return self

    async def ainvoke(self, messages):
        assert 'PRIVATE_TEST_SECRET' not in str(messages)
        assert self.settings['max_retries'] == 0
        if self.schema is evaluation.ConsultationResult:
            value = dict(summary='protocol', questions=[], referenceKeys=['protocol_ref'])
        else:
            from plan12_draft_fixtures import draft_result
            assert self.schema is evaluation.Plan12Draft
            value = draft_result('protocol_ref', 'PROTOCOL_TEST')
        return dict(parsed=self.schema.model_validate(value), parsing_error=None,
                    raw=SimpleNamespace(usage_metadata=dict(input_tokens=12, output_tokens=8, total_tokens=20)))


def test_preflight_is_offline_and_distinguishes_provider_configuration_from_service_authorization(configured):
    report = evaluation.preflight()
    assert report['provider']['configured']
    assert report['liveRequests'] == 0 and not report['serviceConfigurationReady']
    assert 'EXPLORATION_EVIDENCE_DATASET_VERSION' in report['missingServiceSettings']
    assert 'PRIVATE_TEST_SECRET' not in json.dumps(report)


def test_protocol_uses_actual_schemas_but_cannot_claim_content_or_service_approval(configured, monkeypatch):
    calls = []
    def factory(**settings):
        calls.append(settings)
        return ProtocolClient(**settings)
    report = asyncio.run(evaluation.protocol_probe(evaluation.configuration(), factory))
    assert report['status'] == 'PASS' and report['liveRequests'] == 2
    assert [c['max_tokens'] for c in calls] == [1600, 6000]
    assert report['contentQuality'] == 'NOT_EVALUATED'
    assert not report['approvalVerified'] and not report['serviceGenerationTested'] and not report['releaseReady']
    assert all(p['usage']['totalTokens'] == 20 for p in report['probes'])
    assert 'PRIVATE_TEST_SECRET' not in json.dumps(report)
    assert 'EXPLORATION_MODEL_EXECUTION_ENABLED' not in __import__('os').environ


def test_missing_live_opt_in_cannot_call_a_provider(configured, tmp_path, monkeypatch):
    monkeypatch.setattr(evaluation, 'protocol_probe', lambda *a: pytest.fail('No live call'))
    file = tmp_path / 'report.json'
    assert evaluation.main(['--mode', 'protocol', '--output', str(file)]) == 2
    assert json.loads(file.read_text())['reasonCode'] == 'LIVE_OPT_IN_REQUIRED'


@pytest.mark.parametrize('status,code,attempts', [(401, 'MODEL_AUTH_FAILED', 1), (400, 'MODEL_CONFIGURATION_INVALID', 2), (429, 'MODEL_RATE_LIMITED', 2), (503, 'MODEL_UNAVAILABLE', 2)])
def test_provider_errors_never_expose_messages_or_retry(configured, status, code, attempts):
    class Failure(Exception):
        status_code = status
    class Failed(ProtocolClient):
        async def ainvoke(self, messages):
            raise Failure('PRIVATE_TEST_SECRET AND PRIVATE_REQUEST_BODY')
    report = asyncio.run(evaluation.protocol_probe(evaluation.configuration(), Failed))
    assert report['liveRequests'] == attempts
    assert all(p['reasonCode'] == code for p in report['probes'])
    assert 'PRIVATE_' not in json.dumps(report)


def test_timeout_is_bounded_and_not_reported_as_zero_token_cost(configured):
    class Slow(ProtocolClient):
        async def ainvoke(self, messages):
            await asyncio.sleep(10)
    config = evaluation.configuration()
    config['timeout'] = .01
    config['plan_timeout'] = .01
    report = asyncio.run(evaluation.protocol_probe(config, Slow))
    assert all(p['reasonCode'] == 'MODEL_TIMEOUT' and p['estimatedCost'] is None for p in report['probes'])


def test_parse_failure_and_wrong_references_cannot_pass(configured):
    class Invalid(ProtocolClient):
        async def ainvoke(self, messages):
            result = await super().ainvoke(messages)
            result['parsed'] = result['parsed'].model_dump()
            if 'referenceKeys' in result['parsed']:
                result['parsed']['referenceKeys'] = ['forged']
            else:
                result['parsing_error'] = ValueError('PRIVATE_BODY')
            return result
    report = asyncio.run(evaluation.protocol_probe(evaluation.configuration(), Invalid))
    assert all(p['reasonCode'] == 'MODEL_OUTPUT_INVALID' for p in report['probes'])
    assert report['status'] == 'FAILED' and 'PRIVATE_BODY' not in json.dumps(report)


@pytest.mark.parametrize('value', ['nan', '0', '6'])
def test_invalid_deadline_is_rejected_before_network(configured, monkeypatch, value):
    monkeypatch.setenv('EXPLORATION_LLM_TIMEOUT_SECONDS', value)
    assert not evaluation.preflight()['provider']['configured']


def test_environment_file_cannot_be_overwritten(configured, tmp_path):
    env = tmp_path / '.env'
    env.write_text('PRIVATE_TEST_SECRET')
    with pytest.raises(SystemExit):
        evaluation.main(['--env-file', str(env), '--output', str(env)])
    assert env.read_text() == 'PRIVATE_TEST_SECRET'


def test_approved_generation_requires_flags_version_and_owned_request_export(configured, monkeypatch):
    assert evaluation.approved_probe(None, None)['reasonCode'] == 'EVALUATION_SERVICE_DISABLED'
    monkeypatch.setenv('EXPLORATION_MODEL_EXECUTION_ENABLED', 'true')
    monkeypatch.setenv('EXPLORATION_EVIDENCE_ENABLED', 'true')
    assert evaluation.approved_probe(None, None)['reasonCode'] == 'FIXED_DATASET_VERSION_REQUIRED'
    assert evaluation.approved_probe(None, 'TEST_VERSION')['reasonCode'] == 'OWNED_REQUEST_EXPORT_REQUIRED'


def test_usage_only_keeps_nonnegative_integer_counters():
    raw = SimpleNamespace(usage_metadata={'input_tokens': -1, 'output_tokens': True, 'total_tokens': 10, 'private': 'PRIVATE'})
    assert evaluation.usage(raw) == {'totalTokens': 10}


@pytest.mark.parametrize('revoke', [False, True])
def test_approved_evaluation_is_readonly_rechecks_after_call_and_never_saves_a_result(configured, monkeypatch, tmp_path, revoke):
    from app import exploration_model
    from app.rag import exploration_binding as binding
    import sqlalchemy
    import sqlalchemy.orm
    from test_exploration_evidence_binding import request
    value = request(['TEST'])
    path = tmp_path / 'request.json'
    review_path = tmp_path / 'private-review.json'
    path.write_text(json.dumps(value))
    monkeypatch.setenv('EXPLORATION_MODEL_EXECUTION_ENABLED', 'true')
    monkeypatch.setenv('EXPLORATION_EVIDENCE_ENABLED', 'true')
    events = []
    class DB:
        def __init__(self, engine): pass
        def __enter__(self): return self
        def __exit__(self, *args): events.append('end_tx')
        def execute(self, statement):
            assert str(statement) == 'SET TRANSACTION READ ONLY'
            events.append('readonly')
        def commit(self): pytest.fail('No write transaction')
    class Binder:
        def __init__(self, *args): pass
        def prepare(self, original):
            assert original == value
            events.append('prepare')
            return SimpleNamespace(binding='BINDING', request=deepcopy(original))
        def validated_model_evidence(self, prepared):
            events.append('before')
            return []
        def revalidate(self, prepared):
            events.append('after')
            if revoke: raise binding.EvidenceNoLongerAvailable()
    class Consumer:
        def __init__(self, *, evaluation_diagnostics):
            assert evaluation_diagnostics
        timings = {'providerWaitMs': 12.0, 'cleanupMs': 2.0, 'totalMs': 15.0}
        metadata = {'promptVersion': 'TEST_PROMPT'}
        def generate(self, request, facts):
            assert events[-1] == 'end_tx'
            events.append('provider')
            return dict(purpose=value['purpose'], contractVersion=value['contractVersion'], result={'summary': 'SYNTHETIC review content'})
    monkeypatch.setattr(sqlalchemy, 'create_engine', lambda *a, **k: SimpleNamespace(dispose=lambda: events.append('dispose')))
    monkeypatch.setattr(sqlalchemy.orm, 'Session', DB)
    monkeypatch.setattr(binding, 'ExplorationEvidenceBinder', Binder)
    monkeypatch.setattr(exploration_model, 'ExplorationModel', Consumer)
    report = evaluation.approved_probe(path, 'TEST_VERSION', review_path)
    assert report['liveRequests'] == 1 and not report['savesUserResult']
    assert events == ['readonly', 'prepare', 'before', 'end_tx', 'provider', 'readonly', 'after', 'end_tx', 'dispose']
    assert report['status'] == ('BLOCKED' if revoke else 'PASS')
    assert report['contentQuality'] == ('NOT_EVALUATED' if revoke else 'HUMAN_REVIEW_REQUIRED')
    assert review_path.exists() is (not revoke)
    if not revoke:
        assert report['timingsMs'] == Consumer.timings
        assert report['promptVersion'] == 'TEST_PROMPT'
        review = json.loads(review_path.read_text())
        assert review['requiresHumanReview'] and 'requestId' not in review and 'inputHash' not in review
