import json
from scripts.run_java_with_ai_env import ALLOWED, KEYS, child_environment, diagnostic, main
import pytest


@pytest.fixture(autouse=True)
def isolate_real_environment(monkeypatch):
    for key in ALLOWED | KEYS | {'SOURCE_TOKEN'}:
        monkeypatch.delenv(key,raising=False)


def test_quotes_and_token_are_imported_but_api_keys_and_ai_database_are_not(tmp_path):
    from dotenv import dotenv_values
    path=tmp_path/'.env'
    path.write_text('INTERNAL_SERVICE_TOKEN="PRIVATE#TOKEN"\nLLM_MODEL="MODEL"\nLLM_API_KEY=PRIVATE_KEY\nAI_DB_PASSWORD=PRIVATE_DB\n')
    env=child_environment(dotenv_values(path,interpolate=False),{'PATH':'TEST','LLM_API_KEY':'INHERITED_PRIVATE'})
    assert env['AI_SERVER_TOKEN']=='PRIVATE#TOKEN' and env['LLM_MODEL']=='MODEL'
    assert 'LLM_API_KEY' not in env and 'AI_DB_PASSWORD' not in env
    assert env['SPRING_PROFILES_ACTIVE']=='exploration-dev' and env['EXPLORATION_RUN_MODE']=='DEV'
    assert 'EXPLORATION_MODEL_EXECUTION_ENABLED' not in env and 'EXPLORATION_DEV_USER_IDS' not in env
    assert 'PRIVATE' not in json.dumps(diagnostic(env))


def test_parent_environment_wins_and_is_not_mutated():
    parent={'AI_SERVER_TOKEN':'PARENT','LLM_MODEL':'PARENT_MODEL','LLM_API_KEY':'KEY'}
    env=child_environment({'INTERNAL_SERVICE_TOKEN':'FILE','LLM_MODEL':'FILE_MODEL'},parent)
    assert env['AI_SERVER_TOKEN']=='PARENT' and env['LLM_MODEL']=='PARENT_MODEL'
    assert parent['LLM_API_KEY']=='KEY'


@pytest.mark.parametrize('profiles',['prod','production,exploration-dev','dev'])
def test_local_launcher_never_selects_production(profiles):
    with pytest.raises(ValueError):child_environment({}, {}, profiles)


def test_check_does_not_launch_java_or_edit_the_file(tmp_path,monkeypatch,capsys):
    import subprocess
    env=tmp_path/'.env';env.write_text('INTERNAL_SERVICE_TOKEN=PRIVATE_TOKEN\nLLM_MODEL=MODEL\n')
    before=env.read_bytes()
    monkeypatch.setattr(subprocess,'run',lambda *a,**k:pytest.fail('No child process'))
    assert main(['--env-file',str(env),'--check'])==0
    output=capsys.readouterr().out
    assert 'PRIVATE_TOKEN' not in output and env.read_bytes()==before


def test_launch_passes_secrets_in_environment_only_and_never_enables_generation(tmp_path,monkeypatch):
    import subprocess
    from types import SimpleNamespace
    file=tmp_path/'.env';file.write_text('SOURCE_TOKEN=PRIVATE_TOKEN\nINTERNAL_SERVICE_TOKEN=${SOURCE_TOKEN}\nLLM_API_KEY=PRIVATE_KEY\n')
    jar=tmp_path/'app.jar';jar.write_bytes(b'TEST')
    def run(command,*,env,shell):
        assert command[-2:]==['-jar',str(jar.resolve())] and not shell
        assert 'PRIVATE' not in str(command)
        assert 'LLM_API_KEY' not in env and env['AI_SERVER_TOKEN']=='PRIVATE_TOKEN' and 'SOURCE_TOKEN' not in env
        assert env.get('EXPLORATION_MODEL_EXECUTION_ENABLED','false')!='true'
        return SimpleNamespace(returncode=0)
    monkeypatch.delenv('EXPLORATION_MODEL_EXECUTION_ENABLED',raising=False)
    monkeypatch.setattr(subprocess,'run',run)
    monkeypatch.setattr('shutil.which',lambda name:'java')
    assert main(['--env-file',str(file),'--jar',str(jar)])==0
