import asyncio
import json
import pytest
from app.exploration_model import ExplorationModel, ModelFault
from test_exploration_model import FakeChat, bound, settings, REF


def test_provider_and_cleanup_times_are_separate_and_not_in_wire_metadata(settings):
    class Slow(FakeChat):
        async def ainvoke(self, messages):
            await asyncio.sleep(.01)
            return await super().ainvoke(messages)
    class Closing:
        async def close(self): await asyncio.sleep(.01)
    chat = Slow({'summary': 'TEST', 'questions': [], 'referenceKeys': [REF]})
    chat.root_async_client = Closing()
    model = ExplorationModel(chat)
    result = model.generate(*bound())
    assert model.timings['providerWaitMs'] >= 5 and model.timings['cleanupMs'] >= 5
    assert all(value is not None and value >= 0 for value in model.timings.values())
    assert model.timings['totalMs'] >= model.timings['providerWaitMs'] + model.timings['cleanupMs']
    assert set(model.metadata) == {'provider', 'model', 'promptVersion'}
    assert 'timings' not in json.dumps(result) and 'PRIVATE' not in json.dumps(model.timings)
    old = model.timings
    model.generate(*bound())
    assert model.timings is not old


def test_timeout_records_wait_and_cleanup_without_claiming_validation_ran(settings, monkeypatch):
    monkeypatch.setenv('EXPLORATION_LLM_TIMEOUT_SECONDS', '.01')
    class Slow(FakeChat):
        async def ainvoke(self, messages): await asyncio.sleep(10)
    model = ExplorationModel(Slow(None))
    with pytest.raises(ModelFault) as failure: model.generate(*bound())
    assert failure.value.code == 'MODEL_TIMEOUT'
    assert model.timings['providerWaitMs'] is not None
    assert model.timings['validationAndRenderMs'] is None
    assert model.timings['cleanupMs'] is not None and model.timings['totalMs'] > 0
