"""Local Java launcher: import an allowlist from AI dotenv without copying API keys.

--check is offline. No file edits, feature activation, approval or DB operations.
Java MySQL configuration stays in its own process environment/application config.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

ALLOWED = {
    'AI_SERVER_URL', 'AI_SERVER_TOKEN', 'INTERNAL_SERVICE_TOKEN', 'LLM_MODEL', 'EXPLORATION_LLM_MODEL',
    'EXPLORATION_ENABLED', 'EXPLORATION_RUN_MODE', 'EXPLORATION_DEV_USER_IDS',
    'EXPLORATION_JOBS_ENABLED', 'EXPLORATION_JOBS_ERASURE_ENABLED', 'EXPLORATION_EVIDENCE_ENABLED',
    'EXPLORATION_EVIDENCE_DATASET_VERSION', 'EXPLORATION_MODEL_EXECUTION_ENABLED',
    'EXPLORATION_MODEL_CONNECT_TIMEOUT_MS', 'EXPLORATION_MODEL_READ_TIMEOUT_MS',
}
KEYS = {'LLM_API_KEY', 'OPENAI_API_KEY', 'FACTCHAT_API_KEY'}


def child_environment(values, inherited, profiles='exploration-dev'):
    names = [name.strip() for name in profiles.split(',')]
    if 'exploration-dev' not in names or any(name.lower() in {'prod', 'production'} for name in names):
        raise ValueError('Local launcher requires exploration-dev without production profiles')
    merged = dict(inherited)
    for key in ALLOWED:
        if key not in merged and values.get(key) is not None:
            merged[key] = values[key]
    for key in KEYS:
        merged.pop(key, None)
    if 'AI_SERVER_TOKEN' not in merged and merged.get('INTERNAL_SERVICE_TOKEN'):
        merged['AI_SERVER_TOKEN'] = merged['INTERNAL_SERVICE_TOKEN']
    merged.setdefault('AI_SERVER_URL', 'http://127.0.0.1:8001')
    merged.setdefault('EXPLORATION_RUN_MODE', 'DEV')
    if merged['EXPLORATION_RUN_MODE'] != 'DEV':
        raise ValueError('Local launcher only supports DEV mode')
    merged['SPRING_PROFILES_ACTIVE'] = profiles
    return merged


def diagnostic(env):
    flags = ('EXPLORATION_ENABLED', 'EXPLORATION_JOBS_ENABLED', 'EXPLORATION_EVIDENCE_ENABLED',
             'EXPLORATION_MODEL_EXECUTION_ENABLED', 'EXPLORATION_JOBS_ERASURE_ENABLED')
    missing = [key for key in flags if env.get(key, 'false').lower() != 'true']
    for key in ('AI_SERVER_TOKEN', 'EXPLORATION_EVIDENCE_DATASET_VERSION', 'EXPLORATION_DEV_USER_IDS'):
        if not env.get(key, '').strip():
            missing.append(key)
    if not (env.get('EXPLORATION_LLM_MODEL') or env.get('LLM_MODEL')):
        missing.append('LLM_MODEL or EXPLORATION_LLM_MODEL')
    return dict(profiles=env['SPRING_PROFILES_ACTIVE'], runMode=env['EXPLORATION_RUN_MODE'],
                tokenConfigured=bool(env.get('AI_SERVER_TOKEN')), aiServerUrlConfigured=bool(env.get('AI_SERVER_URL')),
                modelConfigured=bool(env.get('EXPLORATION_LLM_MODEL') or env.get('LLM_MODEL')),
                missingGenerationSettings=missing, approvalVerified=False, databaseValidated=False,
                apiKeysPassedToJava=any(key in env for key in KEYS))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=Path(__file__).resolve().parents[1] / '.env')
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--profiles', default='exploration-dev')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args(argv)
    if not args.env_file.is_file():
        parser.error('AI environment file is missing')
    from dotenv import dotenv_values
    try:
        env = child_environment(dotenv_values(args.env_file), os.environ, args.profiles)
    except ValueError:
        parser.error('Local profile/run mode is invalid')
    print(json.dumps(diagnostic(env)))
    if args.check:
        return 0
    if args.jar is None or not args.jar.is_file():
        parser.error('Supply an existing application JAR; this launcher does not build or migrate databases itself')
    java = str(Path(env['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if env.get('JAVA_HOME') else shutil.which('java')
    if not java:
        parser.error('Java executable is unavailable')
    return subprocess.run([java, '-jar', str(args.jar.resolve())], env=env, shell=False).returncode


if __name__ == '__main__':
    raise SystemExit(main())
