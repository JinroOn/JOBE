import asyncio
import pytest
from app.chain_plan import WeeklyPlanChain, PlanChainResult
from test_weekly_plan_contract import build_request


def test_weekly_budget_is_independent_of_generic_legacy_timeout(monkeypatch):
    monkeypatch.setenv('LLM_TIMEOUT_SECONDS','60')
    monkeypatch.delenv('WEEKLY_PLAN_LLM_TIMEOUT_SECONDS',raising=False)
    assert WeeklyPlanChain().timeout_seconds==30


@pytest.mark.parametrize('value',['0','-1','31','inf','nan'])
def test_invalid_weekly_budgets_cannot_construct_provider(monkeypatch,value):
    monkeypatch.setenv('WEEKLY_PLAN_LLM_TIMEOUT_SECONDS',value)
    with pytest.raises(ValueError):WeeklyPlanChain()


def test_deadline_returns_explicit_server_fallback_without_retry(monkeypatch):
    import app.chain_plan as module
    monkeypatch.setenv('WEEKLY_PLAN_LLM_TIMEOUT_SECONDS','.01')
    calls=[]
    class Slow:
        def __init__(self,**settings):
            assert settings['timeout']==.01 and settings['max_retries']==0
        def with_structured_output(self,schema):return self
        async def ainvoke(self,value):
            calls.append('provider')
            await asyncio.sleep(10)
    monkeypatch.setattr(module,'ChatOpenAI',Slow)
    monkeypatch.setattr(module,'enrich_weekly_plan_request_with_rag',lambda request,request_id:(request,[]))
    chain=WeeklyPlanChain();chain.use_mock=False;chain.provider='openai'
    class Prompt:
        def __or__(self,structured):return structured
    monkeypatch.setattr(chain,'_build_prompt',lambda:Prompt())
    result=asyncio.run(chain.generate_with_meta(build_request(weeks=12),'SYNTHETIC_TIMEOUT'))
    assert calls==['provider']
    assert result.response.fallback is True
    assert result.response.fallbackReasons==['llm_timeout_fallback']
    assert len(result.response.weeklyPlan)==12


def test_success_metadata_does_not_claim_fallback():
    chain=WeeklyPlanChain();request=build_request(weeks=12)
    response=chain._mock_response(request=request,request_id='SYNTHETIC_BASE')
    result=PlanChainResult(response,[],chain.prompt_version,chain.model)
    assert result.response.fallback is False and result.response.fallbackReasons==[]
