DEFAULT_LLM_MODEL = 'gpt-6-luna'


def structured_chat_options(model: str) -> dict:
    # LangChain's current structured output path uses Chat Completions function calling.
    return {'model_kwargs': {'reasoning_effort': 'none'}} if model == 'gpt-6-luna' else {}
