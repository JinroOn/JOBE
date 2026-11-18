from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import uuid

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from scripts import apply_verified_rag_migration as apply
from scripts.verify_rag_backup_migration import BASE, HEAD, VerificationFault, manifest, file_hash


@pytest.fixture
def proof_file(tmp_path):
    url=make_url('postgresql://test:TEST@127.0.0.1:5433/source')
    archive=tmp_path/'rag.dump';archive.write_bytes(b'PGDMP_TEST_ONLY')
    proof={'status':'PASS','restoreExact':True,'sourceUnchanged':True,'cloneRemoved':True,
           'startedAt':datetime.now(timezone.utc).isoformat(),'sourceContainer':'TEST_CONTAINER',
           'sourcePort':5433,'sourceDatabase':'source',
           'migrationHashes':{p.name:file_hash(p) for p in (apply.ROOT/'migrations/versions').glob('*.py')},
           'alembicEnvironmentHash':file_hash(apply.ROOT/'migrations/env.py'),
           'backup':{'path':str(archive),'bytes':archive.stat().st_size,'sha256':file_hash(archive)},
           'sourceBefore':{'revision':[BASE]},'cloneAfterMigration':{'revision':[HEAD]}}
    path=tmp_path/'proof.json';path.write_text(json.dumps(proof))
    return path,url,proof


def test_valid_restore_proof_is_accepted(proof_file):
    path,url,proof=proof_file
    assert apply.load_proof(path,url,'TEST_CONTAINER')==proof


@pytest.mark.parametrize('change',['expired','future','failed','source','migration','environment','archive','revision'])
def test_changed_or_unverified_backup_cannot_authorize_application(proof_file,change):
    path,url,proof=proof_file
    if change=='expired':proof['startedAt']=(datetime.now(timezone.utc)-timedelta(hours=2)).isoformat()
    if change=='future':proof['startedAt']=(datetime.now(timezone.utc)+timedelta(hours=2)).isoformat()
    if change=='failed':proof['status']='FAILED'
    if change=='source':proof['sourcePort']=5434
    if change=='migration':proof['migrationHashes']={}
    if change=='environment':proof['alembicEnvironmentHash']='CHANGED'
    if change=='archive':Path(proof['backup']['path']).write_bytes(b'CHANGED')
    if change=='revision':proof['cloneAfterMigration']['revision']=[BASE]
    path.write_text(json.dumps(proof))
    with pytest.raises(VerificationFault):apply.load_proof(path,url,'TEST_CONTAINER')


@pytest.fixture
def isolated_database():
    import psycopg
    from psycopg import sql
    from alembic import command
    from alembic.config import Config
    value=os.getenv('SECTION32_TEST_DATABASE_URL')
    if not value:pytest.skip('Dedicated test PostgreSQL required')
    url=make_url(value)
    assert url.host in {'localhost','127.0.0.1'} and url.database=='section32_ai_test'
    name='rag_apply_test_'+uuid.uuid4().hex
    admin=psycopg.connect(url.set(drivername='postgresql').render_as_string(hide_password=False),autocommit=True)
    admin.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(name)))
    engine=create_engine(url.set(database=name))
    try:
        with engine.begin() as connection:
            config=Config(str(apply.ROOT/'alembic.ini'))
            config.set_main_option('script_location',str(apply.ROOT/'migrations'))
            config.attributes['connection']=connection
            command.upgrade(config,BASE)
            connection.execute(text("""INSERT INTO ai_major_documents(major_key,major_name,source_type,dataset_version,content_hash)
                VALUES ('TEST','TEST','manual','TEST_ONLY',:hash)"""),{'hash':'a'*64})
        with engine.connect() as connection:
            before=manifest(connection.connection.driver_connection)
            connection.rollback()
            transaction=connection.begin()
            apply.upgrade_on_connection(connection)
            expected=manifest(connection.connection.driver_connection)
            transaction.rollback()
            assert manifest(connection.connection.driver_connection)==before
        yield engine,{'sourceBefore':before,'cloneAfterMigration':expected}
    finally:
        engine.dispose()
        admin.execute(sql.SQL('DROP DATABASE {}').format(sql.Identifier(name)))
        admin.close()


def snapshot(engine):
    with engine.connect() as connection:
        return manifest(connection.connection.driver_connection)


def test_check_only_keeps_schema_and_data_at_base(isolated_database):
    engine,proof=isolated_database
    assert apply.apply_transaction(engine,proof,apply=False)==proof['sourceBefore']
    assert snapshot(engine)==proof['sourceBefore']


def test_application_and_preservation_checks_commit_together(isolated_database):
    engine,proof=isolated_database
    assert apply.apply_transaction(engine,proof,apply=True)==proof['cloneAfterMigration']
    assert snapshot(engine)==proof['cloneAfterMigration']


def test_failure_after_ddl_rolls_back_ddl_and_revision_and_preserves_rows(isolated_database,monkeypatch):
    engine,proof=isolated_database
    def reject(*a,**k):raise VerificationFault('SYNTHETIC_PRESERVATION_FAILURE')
    monkeypatch.setattr(apply,'compare_original',reject)
    with pytest.raises(VerificationFault,match='SYNTHETIC_PRESERVATION_FAILURE'):
        apply.apply_transaction(engine,proof,apply=True)
    assert snapshot(engine)==proof['sourceBefore']


def test_changed_source_is_rejected_before_migration(isolated_database):
    engine,proof=isolated_database;changed=deepcopy(proof)
    changed['sourceBefore']['tables']['ai_major_documents']['sha256']='CHANGED'
    with pytest.raises(VerificationFault,match='SOURCE_CHANGED_SINCE_BACKUP'):
        apply.apply_transaction(engine,changed,apply=True)
    assert snapshot(engine)==proof['sourceBefore']


def test_a_competing_application_cannot_take_the_migration_lock(isolated_database):
    engine,proof=isolated_database
    with engine.connect() as holder:
        holder.execute(text('SELECT pg_advisory_lock(:key)'),{'key':apply.LOCK_KEY})
        try:
            with pytest.raises(VerificationFault,match='MIGRATION_ALREADY_RUNNING'):
                apply.apply_transaction(engine,proof,apply=True)
        finally:holder.execute(text('SELECT pg_advisory_unlock(:key)'),{'key':apply.LOCK_KEY})
    assert snapshot(engine)==proof['sourceBefore']


def test_report_cannot_overwrite_environment_or_proof(tmp_path):
    file=tmp_path/'.env';file.write_text('PRIVATE_ENVIRONMENT')
    with pytest.raises(SystemExit):apply.main(['--env-file',str(file),'--verification',str(file),'--report',str(file)])
    assert file.read_text()=='PRIVATE_ENVIRONMENT'


def test_missing_environment_cannot_fall_back_to_a_default_database(tmp_path,monkeypatch):
    monkeypatch.setattr(apply,'execute',lambda *a:pytest.fail('No database action'))
    proof=tmp_path/'proof.json';proof.write_text('{}')
    with pytest.raises(SystemExit):
        apply.main(['--env-file',str(tmp_path/'missing.env'),'--verification',str(proof),'--report',str(tmp_path/'report.json'),'--apply'])
