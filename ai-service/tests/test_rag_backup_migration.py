from copy import deepcopy
from types import SimpleNamespace
import json

import pytest
from sqlalchemy.engine import make_url
from scripts import verify_rag_backup_migration as check


def fixture_manifest(migrated=False):
    value = dict(tables={'ai_major_chunks': {'rows': 2, 'sha256': 'ALL_VECTOR_AND_JSON_CONTENT', 'schemaSha256': 'SCHEMA'},
                         'alembic_version': {'rows': 1, 'sha256': 'VERSION'}},
                 sequences={'ai_major_chunks_id_seq': [42, True]},
                 extensions=[['vector', 'TEST_VERSION']], revision=[check.HEAD if migrated else check.BASE])
    if migrated:
        for table in check.NEW_TABLES:
            value['tables'][table] = {'rows': 0, 'sha256': 'EMPTY'}
    return value


def test_row_fingerprint_preserves_duplicates_and_content_without_exporting_rows():
    private = 'PRIVATE_VECTOR_AND_QUERY'
    value = check.digest_rows([private, 'other', private])
    assert value == check.digest_rows(['other', private, private])
    assert value != check.digest_rows([private, 'other'])
    assert value != check.digest_rows([private, 'changed', private])
    assert private not in json.dumps(value)


def test_exact_restore_and_migration_only_allow_expected_empty_additions():
    before = fixture_manifest()
    check.compare_original(before, deepcopy(before), migrated=False)
    check.compare_original(before, fixture_manifest(True), migrated=True)


@pytest.mark.parametrize('change', ['rows', 'checksum', 'schema', 'sequence', 'extension', 'revision', 'missing'])
def test_restore_rejects_damage_even_when_row_count_looks_correct(change):
    before = fixture_manifest(); after = deepcopy(before)
    if change == 'rows': after['tables']['ai_major_chunks']['rows'] = 1
    if change == 'checksum': after['tables']['ai_major_chunks']['sha256'] = 'CHANGED_VECTOR'
    if change == 'schema': after['tables']['ai_major_chunks']['schemaSha256'] = 'CHANGED_DIMENSION'
    if change == 'sequence': after['sequences']['ai_major_chunks_id_seq'] = [1, False]
    if change == 'extension': after['extensions'] = []
    if change == 'revision': after['revision'] = [check.HEAD]
    if change == 'missing': del after['tables']['ai_major_chunks']
    with pytest.raises(check.VerificationFault): check.compare_original(before, after, migrated=False)


@pytest.mark.parametrize('change', ['approval', 'extra_table', 'missing_table'])
def test_migration_cannot_backfill_approvals_or_change_unexpected_tables(change):
    after = fixture_manifest(True)
    if change == 'approval': after['tables']['ai_evidence_reviews']['rows'] = 1
    if change == 'extra_table': after['tables']['unexpected'] = {'rows': 0}
    if change == 'missing_table': del after['tables']['ai_exploration_jobs']
    with pytest.raises(check.VerificationFault): check.compare_original(fixture_manifest(), after, migrated=True)


def test_source_must_match_published_loopback_port_and_not_clone_port():
    source = make_url('postgresql://user:PRIVATE@localhost:5433/source')
    info = {'NetworkSettings': {'Ports': {'5432/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '5433'}]}}}
    check.check_source_mapping(info, source, 15439)
    with pytest.raises(check.VerificationFault): check.check_source_mapping(info, source, 5433)
    with pytest.raises(check.VerificationFault): check.check_source_mapping({}, source, 15439)


def test_migration_child_cannot_target_nonlocal_or_non_test_database(monkeypatch):
    for target in ('postgresql+psycopg://user:PRIVATE@remote:5432/rag_restore_test',
                   'postgresql+psycopg://user:PRIVATE@127.0.0.1:5433/jobe_ai'):
        with pytest.raises(check.VerificationFault): check.child_env(target)
    monkeypatch.setenv('LLM_API_KEY', 'PRIVATE_REAL_KEY')
    env = check.child_env('postgresql+psycopg://test:TEST@127.0.0.1:15439/rag_restore_test')
    assert env['LLM_API_KEY'] == 'SYNTHETIC_BACKUP_CHECK_ONLY'
    assert env['AI_DATABASE_URL'] == env['SECTION32_TEST_DATABASE_URL']


def test_source_url_is_local_and_does_not_need_model_keys(tmp_path, monkeypatch):
    monkeypatch.delenv('AI_DATABASE_URL', raising=False)
    file = tmp_path / '.env'
    file.write_text('AI_DATABASE_URL=postgresql://user:PRIVATE@localhost:5433/source\n')
    assert check.source_url(file).port == 5433
    file.write_text('AI_DATABASE_URL=postgresql://user:PRIVATE@remote:5433/source\n')
    with pytest.raises(check.VerificationFault): check.source_url(file)


def test_command_failure_never_exposes_raw_stderr_or_credentials(monkeypatch):
    monkeypatch.setattr(check.subprocess, 'run', lambda *a, **k: SimpleNamespace(returncode=1, stdout=b'', stderr=b'PRIVATE_CONNECTION_AND_DATA'))
    with pytest.raises(check.VerificationFault) as error:
        check.run(['TEST'], code='BACKUP_FAILED')
    assert str(error.value) == 'BACKUP_FAILED'


def test_existing_environment_file_cannot_be_used_as_report(tmp_path):
    file = tmp_path / '.env'; file.write_text('PRIVATE_CONFIGURATION')
    with pytest.raises(SystemExit):
        check.main(['--env-file', str(file), '--backup-dir', str(tmp_path / 'backup'), '--report', str(file)])
    assert file.read_text() == 'PRIVATE_CONFIGURATION'


def test_backup_inside_repository_is_rejected_before_any_container_or_file_mutation(tmp_path, monkeypatch):
    monkeypatch.setattr(check, 'source_url', lambda path: make_url('postgresql://test:TEST@127.0.0.1:5433/source'))
    monkeypatch.setattr(check, 'inspect_container', lambda *a: pytest.fail('No Docker action'))
    args = SimpleNamespace(env_file=tmp_path/'.env', report=tmp_path/'report.json',
                           backup_dir=check.REPO/'backups', clone_port=15439)
    with pytest.raises(check.VerificationFault, match='BACKUP_MUST_STAY_OUTSIDE_REPOSITORY'):
        check.verify(args)
    assert not args.report.exists()
