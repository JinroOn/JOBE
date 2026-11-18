"""Back up a local Docker RAG database and verify migrations on an owned clone only.

The source is always READ ONLY. Dumps stay outside the repository. No source
migration, approvals, provider calls, or user-result writes are performed.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
HEAD = '20261117_0007'
BASE = '20260604_0001'
NEW_TABLES = {'ai_evidence_reviews', 'ai_exploration_jobs', 'ai_exploration_erased_owners'}
LABEL = 'jobe.rag-backup-check'


class VerificationFault(RuntimeError):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def require(ok, code):
    if not ok:
        raise VerificationFault(code)


def digest_rows(rows):
    hashes = sorted(hashlib.sha256(row.encode('utf-8')).digest() for row in rows)
    return {'rows': len(hashes), 'sha256': hashlib.sha256(b''.join(hashes)).hexdigest()}


def file_hash(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def run(command, *, code, timeout=180, env=None, stdin=None, stdout=None):
    result = subprocess.run(command, cwd=ROOT, env=env, stdin=stdin,
                            stdout=stdout if stdout is not None else subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=timeout, shell=False)
    require(result.returncode == 0, code)
    return result.stdout


def source_url(env_file):
    from dotenv import dotenv_values
    from sqlalchemy.engine import make_url, URL
    values = {**{k: v for k, v in dotenv_values(env_file).items() if v is not None}, **os.environ}
    if values.get('AI_DATABASE_URL', '').strip():
        url = make_url(values['AI_DATABASE_URL'])
    else:
        url = URL.create('postgresql', username=values.get('AI_DB_USER', 'jobe_ai'),
            password=values.get('AI_DB_PASSWORD', 'root'), host=values.get('AI_DB_HOST', 'localhost'),
            port=int(values.get('AI_DB_PORT', '5433')), database=values.get('AI_DB_NAME', 'jobe_ai'))
    require(url.drivername in {'postgresql', 'postgresql+psycopg'} and
            url.host in {'localhost', '127.0.0.1', '::1'}, 'SOURCE_MUST_BE_LOCAL_POSTGRES')
    # Keep SSL/options in explicit URLs, but do not let URI options remove readonly mode below.
    return url.set(drivername='postgresql')


@contextmanager
def readonly(url):
    import psycopg
    with psycopg.connect(url.render_as_string(hide_password=False), connect_timeout=3,
            options='-c default_transaction_read_only=on -c statement_timeout=60000') as connection:
        connection.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY')
        yield connection


def manifest(connection):
    from psycopg import sql
    tables = [row[0] for row in connection.execute("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")]
    result = {'tables': {}, 'sequences': {}, 'revision': [], 'extensions': []}
    for table in tables:
        query = sql.SQL('SELECT to_jsonb(t)::text FROM public.{} AS t').format(sql.Identifier(table))
        with connection.cursor(name='fingerprint_' + uuid.uuid4().hex) as cursor:
            cursor.execute(query)
            content = digest_rows(row[0] for row in cursor)
        columns = list(connection.execute("""SELECT a.attname,format_type(a.atttypid,a.atttypmod),a.attnotnull,
            pg_get_expr(d.adbin,d.adrelid) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
            JOIN pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
            WHERE n.nspname='public' AND c.relname=%s AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum""", (table,)))
        constraints = list(connection.execute("""SELECT conname,pg_get_constraintdef(oid) FROM pg_constraint
            WHERE conrelid=('public.'||quote_ident(%s))::regclass ORDER BY conname""", (table,)))
        indexes = list(connection.execute("SELECT indexname,indexdef FROM pg_indexes WHERE schemaname='public' AND tablename=%s ORDER BY indexname", (table,)))
        schema = json.dumps([columns, constraints, indexes], sort_keys=True, default=str)
        content['schemaSha256'] = hashlib.sha256(schema.encode()).hexdigest()
        content['columns'] = [{'name': c[0], 'type': c[1]} for c in columns]
        result['tables'][table] = content
    for (sequence,) in connection.execute("SELECT sequencename FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename"):
        row = connection.execute(sql.SQL('SELECT last_value,is_called FROM public.{}').format(sql.Identifier(sequence))).fetchone()
        result['sequences'][sequence] = list(row)
    if 'alembic_version' in tables:
        result['revision'] = sorted(row[0] for row in connection.execute('SELECT version_num FROM alembic_version'))
    result['extensions'] = [list(r) for r in connection.execute('SELECT extname,extversion FROM pg_extension ORDER BY extname')]
    return result


def compare_original(before, after, *, migrated):
    expected = HEAD if migrated else BASE
    require(after['revision'] == [expected], 'UNEXPECTED_MIGRATION_REVISION')
    for name, value in before['tables'].items():
        if migrated and name == 'alembic_version':
            continue
        require(after['tables'].get(name) == value, 'EXISTING_TABLE_CHANGED')
    require(all(after['sequences'].get(k) == v for k, v in before['sequences'].items()), 'EXISTING_SEQUENCE_CHANGED')
    require(after['extensions'] == before['extensions'], 'EXTENSION_CHANGED')
    extra = set(after['tables']) - set(before['tables'])
    require(extra == (NEW_TABLES if migrated else set()), 'UNEXPECTED_SCHEMA_CHANGE')
    require(all(after['tables'][name]['rows'] == 0 for name in extra), 'MIGRATION_CREATED_PERSONAL_OR_APPROVAL_DATA')


def child_env(clone_url):
    from sqlalchemy.engine import make_url
    target = make_url(clone_url)
    require(target.host == '127.0.0.1' and target.database in {'rag_restore_test', 'rag_regression_test'}, 'INVALID_CLONE_TARGET')
    env = dict(os.environ)
    env['AI_DATABASE_URL'] = clone_url
    env['SECTION32_TEST_DATABASE_URL'] = clone_url
    for name in ('LLM_API_KEY', 'OPENAI_API_KEY', 'FACTCHAT_API_KEY', 'INTERNAL_SERVICE_TOKEN', 'AI_DB_PASSWORD'):
        env[name] = 'SYNTHETIC_BACKUP_CHECK_ONLY'
    env['MOCK_MODE'] = 'true'
    env['PYTHONDONTWRITEBYTECODE'] = '1'
    return env


def inspect_container(name):
    return json.loads(run(['docker', 'inspect', name], code='CONTAINER_INSPECTION_FAILED'))[0]


def check_source_mapping(info, source, clone_port):
    require(source.port != clone_port, 'SOURCE_CLONE_PORT_COLLISION')
    ports = info.get('NetworkSettings', {}).get('Ports', {}).get('5432/tcp') or []
    require(any(int(p['HostPort']) == source.port and p['HostIp'] in {'127.0.0.1', '::1'} for p in ports), 'SOURCE_CONTAINER_ENDPOINT_MISMATCH')


def probes(clone_url):
    """Only the owned clone is written; every probe row is rolled back."""
    import psycopg
    from sqlalchemy import create_engine
    from sqlalchemy.orm import Session
    from app.exploration_contract import input_hash
    from app.rag.exploration_binding import EvidencePolicy, ExplorationEvidenceBinder
    from sqlalchemy import text
    checks = {}
    with psycopg.connect(clone_url.replace('postgresql+psycopg:', 'postgresql:'), autocommit=True) as con:
        document = con.execute('SELECT id,major_name,dataset_version,content_hash FROM ai_major_documents ORDER BY id LIMIT 1').fetchone()
        require(document is not None, 'NO_RESTORED_DOCUMENTS')
        with con.transaction(force_rollback=True):
            rid = con.execute("""INSERT INTO ai_evidence_reviews(document_id,dataset_version,content_hash,scope,decision,
                content_status,implementation_status,domain_status,reviewer_id,source_reference)
                VALUES (%s,%s,%s,'DEV','REVOKED','DRAFT','REJECTED','REJECTED','SYNTHETIC_BACKUP_CHECK','SYNTHETIC_CLONE_ONLY') RETURNING id""",
                (document[0], document[2], document[3])).fetchone()[0]
            def rejected(name, query, params=()):
                try:
                    with con.transaction():
                        con.execute(query, params)
                except psycopg.Error as error:
                    require(error.sqlstate in {'23514', '23505', 'P0001'}, 'UNEXPECTED_PROBE_ERROR')
                    checks[name] = True
                    return
                raise VerificationFault('CONSTRAINT_PROBE_DID_NOT_REJECT')
            rejected('review_append_only', 'UPDATE ai_evidence_reviews SET reviewer_id=%s WHERE id=%s', ('CHANGED', rid))
            owner = str(uuid.uuid4())
            jid = con.execute("""INSERT INTO ai_exploration_jobs(owner_key,mutation_key,binding_hash,binding,state,attempt)
                VALUES (%s,'clone-check',%s,'{}','QUEUED',0) RETURNING id""", (owner, '0' * 64)).fetchone()[0]
            rejected('invalid_lease_rejected', "UPDATE ai_exploration_jobs SET state='LEASED' WHERE id=%s", (jid,))
            rejected('immutable_job_binding', "UPDATE ai_exploration_jobs SET binding_hash=%s WHERE id=%s", ('1' * 64, jid))
            con.execute('UPDATE ai_exploration_jobs SET attempt=1 WHERE id=%s', (jid,))
            rejected('immutable_execution_mode', "UPDATE ai_exploration_jobs SET execution_mode='MODEL' WHERE id=%s", (jid,))
            rejected('invalid_model_completion', "UPDATE ai_exploration_jobs SET state='MODEL_COMPLETED' WHERE id=%s", (jid,))
        require(con.execute('SELECT count(*) FROM ai_evidence_reviews').fetchone()[0] == 0 and
                con.execute('SELECT count(*) FROM ai_exploration_jobs').fetchone()[0] == 0, 'PROBE_ROWS_NOT_ROLLED_BACK')
    engine = create_engine(clone_url)
    try:
        with Session(engine) as db:
            db.execute(text('SET TRANSACTION READ ONLY'))
            vectors = json.loads((REPO / 'datasets/exploration-ai-contract/cases.json').read_text(encoding='utf-8'))
            request = vectors['adapterCases'][0]['expectedRequest']
            request['input']['candidateNames'] = [document[1]]
            request['input']['targetName'] = None
            request['inputHash'] = input_hash(request)
            prepared = ExplorationEvidenceBinder(db, EvidencePolicy(document[2])).prepare(request)
            require(prepared.binding is None and prepared.status == 'INSUFFICIENT_EVIDENCE', 'UNREVIEWED_SOURCE_WAS_AUTHORIZED')
            checks['unreviewed_restored_content_blocked'] = True
    finally:
        engine.dispose()
    return checks


def verify(args):
    from sqlalchemy.engine import URL
    run_id = uuid.uuid4().hex
    clone = 'jobe-rag-backup-check-' + run_id[:12]
    password = uuid.uuid4().hex
    report = {'reportVersion': 'RAG_BACKUP_MIGRATION_CHECK_V1_0', 'runId': run_id,
              'startedAt': datetime.now(timezone.utc).isoformat(), 'status': 'RUNNING',
              'sourceReadOnly': True, 'sourceMigrated': False, 'approvalsCreated': False,
              'providerCalls': 0, 'cloneContainer': clone, 'cloneRemoved': False}
    source = source_url(args.env_file)
    report['sourceContainer'] = args.source_container
    report['sourcePort'] = source.port
    report['sourceDatabase'] = source.database
    report['migrationHashes'] = {p.name: file_hash(p) for p in (ROOT / 'migrations/versions').glob('*.py')}
    report['alembicEnvironmentHash'] = file_hash(ROOT / 'migrations/env.py')
    require(not args.report.exists(), 'REPORT_ALREADY_EXISTS')
    backup_root = args.backup_dir.resolve()
    require(backup_root != REPO and REPO not in backup_root.parents, 'BACKUP_MUST_STAY_OUTSIDE_REPOSITORY')
    folder = backup_root / run_id
    folder.mkdir(parents=True, exist_ok=False)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    def save(stage):
        report['stage'] = stage
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    save('INSPECT_SOURCE')
    before = None
    created = False
    try:
        info = inspect_container(args.source_container)
        check_source_mapping(info, source, args.clone_port)
        with readonly(source) as con:
            before = manifest(con)
            require(before['revision'] == [BASE], 'SOURCE_REVISION_MUST_BE_0001')
            snapshot = con.execute('SELECT pg_export_snapshot()').fetchone()[0]
            report['sourceBefore'] = before
            save('BACKUP')
            dump = folder / 'rag.dump'
            with dump.open('xb') as stream:
                run(['docker', 'exec', args.source_container, 'pg_dump', '-U', source.username, '-d', source.database,
                     '--format=custom', '--no-owner', '--no-acl', '--snapshot=' + snapshot], stdout=stream,
                    code='BACKUP_FAILED', timeout=300)
                stream.flush(); os.fsync(stream.fileno())
            require(dump.stat().st_size > 0, 'EMPTY_BACKUP')
            report['backup'] = {'path': str(dump), 'bytes': dump.stat().st_size, 'sha256': file_hash(dump),
                                'format': 'PG_CUSTOM', 'consistentExportedSnapshot': True, 'ownersAndPrivilegesIncluded': False}
        save('CREATE_OWNED_CLONE')
        env = dict(os.environ, POSTGRES_USER='backup_check', POSTGRES_DB='rag_restore_test', POSTGRES_PASSWORD=password)
        # Mark ownership before invoking Docker, so a partially created container is handled too.
        created = True
        run(['docker', 'run', '-d', '--name', clone, '--label', LABEL + '=' + run_id,
             '-e', 'POSTGRES_USER', '-e', 'POSTGRES_DB', '-e', 'POSTGRES_PASSWORD',
             '-p', '127.0.0.1:' + str(args.clone_port) + ':5432', '--tmpfs', '/var/lib/postgresql/data', info['Image']],
            env=env, code='CLONE_START_FAILED')
        deadline = time.monotonic() + 45
        while True:
            try:
                run(['docker', 'exec', clone, 'pg_isready', '-U', 'backup_check', '-d', 'rag_restore_test'], code='CLONE_NOT_READY', timeout=3)
                break
            except VerificationFault:
                require(time.monotonic() < deadline, 'CLONE_NOT_READY')
                time.sleep(.5)
        clone_url = URL.create('postgresql+psycopg', username='backup_check', password=password,
                              host='127.0.0.1', port=args.clone_port, database='rag_restore_test')
        save('RESTORE')
        with dump.open('rb') as stream:
            run(['docker', 'exec', '-i', clone, 'pg_restore', '-U', 'backup_check', '-d', 'rag_restore_test',
                 '--exit-on-error', '--single-transaction', '--no-owner', '--no-acl'], stdin=stream, code='RESTORE_FAILED', timeout=300)
        with readonly(clone_url.set(drivername='postgresql')) as con:
            restored = manifest(con)
        compare_original(before, restored, migrated=False)
        report['restoreExact'] = True
        save('MIGRATE_CLONE')
        run([sys.executable, '-m', 'alembic', 'upgrade', HEAD], env=child_env(clone_url.render_as_string(hide_password=False)), code='CLONE_MIGRATION_FAILED')
        with readonly(clone_url.set(drivername='postgresql')) as con:
            migrated = manifest(con)
        compare_original(before, migrated, migrated=True)
        report['cloneAfterMigration'] = migrated
        save('PROBE_CONSTRAINTS')
        report['constraintChecks'] = probes(clone_url.render_as_string(hide_password=False))
        with readonly(clone_url.set(drivername='postgresql')) as con:
            after_probes = manifest(con)
        compare_original(before, after_probes, migrated=True)
        report['probeRowsRolledBack'] = True
        report['status'] = 'PASS'
    except Exception as error:
        report['status'] = 'FAILED'
        report['reasonCode'] = error.code if isinstance(error, VerificationFault) else 'VERIFICATION_FAILED'
    finally:
        if before is not None:
            try:
                with readonly(source) as con:
                    after = manifest(con)
                report['sourceAfter'] = after
                report['sourceUnchanged'] = after == before
                if after != before:
                    report.update(status='FAILED', reasonCode='SOURCE_CHANGED_DURING_CHECK')
            except Exception:
                report.update(status='FAILED', reasonCode='SOURCE_RECHECK_FAILED')
        if created:
            try:
                owned = inspect_container(clone)
                require(owned.get('Config', {}).get('Labels', {}).get(LABEL) == run_id, 'CLONE_OWNERSHIP_MISMATCH')
                run(['docker', 'rm', '-f', clone], code='CLONE_CLEANUP_FAILED')
                report['cloneRemoved'] = True
            except Exception:
                report.update(status='FAILED', cleanupRequired=True)
        save('FINISHED')
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=ROOT / '.env')
    parser.add_argument('--source-container', default='jobe-ai-postgres')
    parser.add_argument('--clone-port', type=int, default=15439)
    parser.add_argument('--backup-dir', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args(argv)
    if not args.env_file.is_file() or not 1024 <= args.clone_port <= 65535:
        parser.error('An existing environment file and an unprivileged clone port are required')
    if args.report.resolve() == args.env_file.resolve() or args.report.name.startswith('.env'):
        parser.error('Report must not overwrite environment configuration')
    try:
        result = verify(args)
    except Exception as error:
        result = {'status': 'FAILED', 'reasonCode': error.code if isinstance(error, VerificationFault) else 'VERIFICATION_FAILED'}
    print(json.dumps({k: result[k] for k in ('status', 'reasonCode') if k in result}))
    return 0 if result['status'] == 'PASS' else 2


if __name__ == '__main__':
    if str(ROOT) not in sys.path:
        sys.path.insert(0, str(ROOT))
    raise SystemExit(main())
