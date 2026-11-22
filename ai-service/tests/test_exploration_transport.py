from contextlib import contextmanager
from copy import deepcopy
import os

from fastapi.testclient import TestClient
import pytest
from sqlalchemy import create_engine, update
from sqlalchemy.orm import Session

from app.main import app, rate_limiter
from app import exploration_transport as transport
from app.rag.approval import AiEvidenceReview
from app.rag.models import AiMajorChunk
from app.rag.exploration_binding import EvidenceIntegrityError, EvidenceNoLongerAvailable, EvidenceStoreUnavailable
from test_exploration_evidence_binding import document, review, request, VERSION

ENDPOINT = '/v1/exploration/evidence/prepare'


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv('INTERNAL_SERVICE_TOKEN', 'transport-test-only')
    monkeypatch.setenv('EXPLORATION_EVIDENCE_ENABLED', 'true')
    monkeypatch.setenv('EXPLORATION_EVIDENCE_DATASET_VERSION', VERSION)
    rate_limiter.window.clear()
    with TestClient(app, raise_server_exceptions=False) as value:
        yield value
    rate_limiter.window.clear()


@pytest.fixture
def store(monkeypatch):
    url = os.getenv('SECTION32_TEST_DATABASE_URL')
    if not url: pytest.skip('Requires migrated disposable PostgreSQL')
    engine = create_engine(url)
    with engine.connect() as connection:
        tx = connection.begin()
        with Session(bind=connection) as db:
            @contextmanager
            def supplied(): yield db
            monkeypatch.setattr(transport, 'evidence_session', supplied)
            yield db
        if tx.is_active: tx.rollback()
    engine.dispose()


def post(client, value):
    return client.post(ENDPOINT, json=value, headers={'Authorization': 'Bearer transport-test-only'})


@pytest.mark.parametrize('token', [None, 'wrong'])
def test_auth_precedes_any_lookup(client, monkeypatch, token):
    def forbidden(*a, **k): pytest.fail('Unauthorized database access')
    monkeypatch.setattr(transport, 'evidence_session', forbidden)
    headers = {} if token is None else {'Authorization': 'Bearer '+token}
    assert client.post(ENDPOINT, json=request(['TEST']), headers=headers).status_code == 401


def test_default_disabled_and_missing_fixed_version_do_not_search(client, monkeypatch):
    def forbidden(*a, **k): pytest.fail('Invalid policy database access')
    monkeypatch.setattr(transport, 'evidence_session', forbidden)
    monkeypatch.delenv('EXPLORATION_EVIDENCE_ENABLED')
    assert post(client, request(['TEST'])).json()['code'] == 'EVIDENCE_BINDING_DISABLED'
    monkeypatch.setenv('EXPLORATION_EVIDENCE_ENABLED', 'true')
    monkeypatch.delenv('EXPLORATION_EVIDENCE_DATASET_VERSION')
    assert post(client, request(['TEST'])).json()['code'] == 'EVIDENCE_POLICY_INVALID'


def test_actual_database_bound_response_is_minimal_and_never_authorizes(client, store):
    doc, _ = document(store); review(store, doc)
    original = request([doc.major_name]); saved = deepcopy(original)
    response = post(client, original)
    assert response.status_code == 200
    body = response.json()
    assert set(body) == {'transportVersion','status','reasonCode','generationAuthorized','request'}
    assert body['status'] == 'BOUND' and body['generationAuthorized'] is False
    assert body['request']['evidence']['datasetVersion'] == VERSION
    assert body['request']['input'] == original['input'] and original == saved
    assert 'sourcePath' not in response.text and 'SYNTHETIC_TEST_REVIEWER' not in response.text
    assert 'DO_NOT_FORWARD' not in response.text and 'documentId' not in response.text


def test_unapproved_missing_and_revoked_are_insufficient(client, store):
    doc, _ = document(store)
    original = request([doc.major_name])
    assert post(client, original).json()['status'] == 'INSUFFICIENT_EVIDENCE'
    review(store, doc); review(store, doc, decision='REVOKED', manifest=False)
    body = post(client, original).json()
    assert body['status'] == 'INSUFFICIENT_EVIDENCE' and body['request'] == original
    assert body['reasonCode'] == 'APPROVED_EVIDENCE_UNAVAILABLE'


def test_real_tampered_row_returns_integrity_and_not_empty_evidence(client, store):
    doc, chunk = document(store); review(store, doc)
    store.execute(update(AiMajorChunk).where(AiMajorChunk.id == chunk.id).values(chunk_text='TAMPERED_PRIVATE'))
    response = post(client, request([doc.major_name]))
    assert response.status_code == 503 and response.json()['code'] == 'EVIDENCE_INTEGRITY_ERROR'
    assert 'TAMPERED_PRIVATE' not in response.text


@pytest.mark.parametrize('failure,code,status,retry', [
    (EvidenceStoreUnavailable,'EVIDENCE_STORE_UNAVAILABLE',503,True),
    (EvidenceNoLongerAvailable,'EVIDENCE_NO_LONGER_AVAILABLE',409,False),
    (EvidenceIntegrityError,'EVIDENCE_INTEGRITY_ERROR',503,False),
])
def test_failures_have_fixed_codes(client, monkeypatch, failure, code, status, retry):
    @contextmanager
    def failed(): raise failure(); yield
    monkeypatch.setattr(transport, 'evidence_session', failed)
    response = post(client, request(['TEST']))
    assert response.status_code == status and response.json()['code'] == code
    assert response.json()['retryable'] is retry


def test_invalid_hash_unknown_fields_and_client_bound_never_search(client, monkeypatch):
    def forbidden(*a, **k): pytest.fail('Invalid request database access')
    monkeypatch.setattr(transport, 'evidence_session', forbidden)
    for change in ['hash','extra','bound']:
        value = request(['TEST'])
        if change == 'hash': value['inputHash'] = '0'*64
        elif change == 'extra': value['private'] = 'DO_NOT_DISCLOSE'
        else:
            from app.exploration_contract import input_hash
            value['evidence'] = {'approvedOnly':True,'bindingStatus':'BOUND','datasetVersion':VERSION,'referenceKeys':['forged']}
            value['inputHash'] = input_hash(value)
        response=post(client,value)
        assert response.status_code == 422 and response.json()['code'] == 'EVIDENCE_CONTRACT_INVALID'
        assert 'DO_NOT_DISCLOSE' not in response.text


def test_rate_limit_does_not_query(client, monkeypatch):
    def forbidden(*a, **k): pytest.fail('Rate limited database access')
    monkeypatch.setattr(transport, 'evidence_session', forbidden)
    import time
    rate_limiter.window.extend([time.time()] * rate_limiter.config.requests_per_minute)
    assert post(client, request(['TEST'])).status_code == 429
