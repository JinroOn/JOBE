"""Apply the verified 0001 -> 0007 migration to a local development RAG DB.

Requires a fresh successful restore drill and matching backup/source/code hashes.
Default is check-only; --apply explicitly permits the atomic source migration.
No automatic restore, content approval, feature activation or provider call.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.verify_rag_backup_migration import (BASE, HEAD, VerificationFault, require,
    source_url, readonly, manifest, compare_original, file_hash, inspect_container, check_source_mapping)

LOCK_KEY = 76003132


def load_proof(path, source, container):
    proof = json.loads(path.read_text(encoding='utf-8'))
    require(proof.get('status') == 'PASS' and proof.get('restoreExact') is True
            and proof.get('sourceUnchanged') is True and proof.get('cloneRemoved') is True,
            'SUCCESSFUL_RESTORE_DRILL_REQUIRED')
    age = (datetime.now(timezone.utc) - datetime.fromisoformat(proof['startedAt'])).total_seconds()
    require(-60 <= age <= 3600, 'RESTORE_DRILL_EXPIRED')
    require(proof.get('sourceContainer') == container and proof.get('sourcePort') == source.port
            and proof.get('sourceDatabase') == source.database, 'RESTORE_DRILL_SOURCE_MISMATCH')
    hashes = {p.name: file_hash(p) for p in (ROOT / 'migrations/versions').glob('*.py')}
    require(proof.get('migrationHashes') == hashes and proof.get('alembicEnvironmentHash') == file_hash(ROOT / 'migrations/env.py'),
            'MIGRATION_CODE_CHANGED_SINCE_DRILL')
    archive = Path(proof['backup']['path']).resolve()
    require(ROOT.parent not in archive.parents and archive.is_file()
            and archive.stat().st_size == proof['backup']['bytes']
            and file_hash(archive) == proof['backup']['sha256'], 'VERIFIED_BACKUP_UNAVAILABLE')
    require(proof['sourceBefore']['revision'] == [BASE] and proof['cloneAfterMigration']['revision'] == [HEAD],
            'RESTORE_DRILL_REVISION_MISMATCH')
    return proof


def upgrade_on_connection(connection):
    from alembic import command
    from alembic.config import Config
    config = Config(str(ROOT / 'alembic.ini'))
    config.set_main_option('script_location', str(ROOT / 'migrations'))
    config.attributes['connection'] = connection
    command.upgrade(config, HEAD)


def apply_transaction(engine, proof, *, apply):
    from psycopg import sql
    from sqlalchemy import text
    with engine.begin() as connection:
        if not apply:
            connection.execute(text('SET TRANSACTION READ ONLY'))
        connection.execute(text("SET LOCAL lock_timeout='5s'"))
        connection.execute(text("SET LOCAL statement_timeout='60s'"))
        require(connection.scalar(text('SELECT pg_try_advisory_xact_lock(:key)'), {'key': LOCK_KEY}), 'MIGRATION_ALREADY_RUNNING')
        raw = connection.connection.driver_connection
        if apply:
            writers = connection.scalar(text("""SELECT count(*) FROM pg_stat_activity
                WHERE datname=current_database() AND pid<>pg_backend_pid() AND backend_type='client backend'
                AND application_name<>'pg_isready' AND state<>'idle'"""))
            require(writers == 0, 'OTHER_ACTIVE_DATABASE_CLIENTS')
            tables = list(proof['sourceBefore']['tables'])
            raw.execute(sql.SQL('LOCK TABLE {} IN SHARE MODE').format(
                sql.SQL(',').join(sql.Identifier('public', name) for name in tables)))
        before = manifest(raw)
        require(before == proof['sourceBefore'], 'SOURCE_CHANGED_SINCE_BACKUP')
        if not apply:
            return before
        # This command uses the supplied transaction. Any validation error below
        # rolls back both DDL and alembic_version, rather than committing first.
        upgrade_on_connection(connection)
        after = manifest(raw)
        compare_original(before, after, migrated=True)
        require(after == proof['cloneAfterMigration'], 'APPLIED_SCHEMA_DIFFERS_FROM_VERIFIED_CLONE')
    return after


def execute(args):
    from sqlalchemy import create_engine
    source = source_url(args.env_file)
    proof = load_proof(args.verification, source, args.source_container)
    info = inspect_container(args.source_container)
    check_source_mapping(info, source, -1)
    require(not args.report.exists(), 'REPORT_ALREADY_EXISTS')
    report = {'reportVersion': 'VERIFIED_RAG_MIGRATION_APPLICATION_V1_0',
              'startedAt': datetime.now(timezone.utc).isoformat(), 'status': 'RUNNING',
              'mode': 'APPLY' if args.apply else 'CHECK_ONLY', 'migrationState': 'NOT_APPLIED',
              'backupSha256': proof['backup']['sha256'], 'proofRunId': proof['runId'],
              'approvalsCreated': False, 'providerCalls': 0, 'userEnvModified': False}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    def save():
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    save()
    engine = create_engine(source.set(drivername='postgresql+psycopg'), connect_args={'connect_timeout': 3})
    try:
        after = apply_transaction(engine, proof, apply=args.apply)
        report['migrationState'] = 'COMMITTED' if args.apply else 'NOT_APPLIED'
        save()
        with readonly(source) as connection:
            persisted = manifest(connection)
        require(persisted == after, 'PERSISTED_DATABASE_VERIFICATION_FAILED')
        report.update(status='PASS', databaseAfter=persisted, existingDataPreserved=True,
                      newTablesEmpty=args.apply, backupVerified=True)
    except Exception as error:
        report.update(status='FAILED', reasonCode=error.code if isinstance(error, VerificationFault) else 'MIGRATION_APPLICATION_FAILED')
        try:
            with readonly(source) as connection:
                actual = manifest(connection)
            if actual == proof['sourceBefore']:
                report['migrationState'] = 'NOT_APPLIED_OR_ROLLED_BACK'
            elif actual['revision'] == [HEAD]:
                report['migrationState'] = 'HEAD_PRESENT_RECHECK_REQUIRED'
            else:
                report['migrationState'] = 'UNKNOWN_RECHECK_REQUIRED'
            report['databaseAfterFailure'] = actual
        except Exception:
            report['migrationState'] = 'UNKNOWN_RECHECK_REQUIRED'
    finally:
        engine.dispose()
        save()
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=ROOT / '.env')
    parser.add_argument('--source-container', default='jobe-ai-postgres')
    parser.add_argument('--verification', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args(argv)
    if not args.env_file.is_file() or not args.verification.is_file():
        parser.error('Existing environment and restore verification files are required')
    if args.report.name.startswith('.env') or args.report.resolve() in {args.env_file.resolve(), args.verification.resolve()}:
        parser.error('Report must be separate from environment and restore evidence')
    try:
        result = execute(args)
    except Exception as error:
        result = {'status': 'FAILED', 'reasonCode': error.code if isinstance(error, VerificationFault) else 'MIGRATION_APPLICATION_FAILED'}
    print(json.dumps({k: result[k] for k in ('status', 'mode', 'migrationState', 'reasonCode') if k in result}))
    return 0 if result['status'] == 'PASS' else 2


if __name__ == '__main__':
    raise SystemExit(main())
