# AI Service (Step 1 MVP)

FastAPI + LangChain service for:

- `POST /v1/recommendation/comment`
- `POST /v1/plan/weekly`

This service follows the contract in:

- `docs/api-contract-v1.md`
- `docs/schemas/*.json`

## 1. Setup

From the JOBE-develop repository root:

```powershell
cd C:\proj\JOBE\JOBE-develop\ai-service
python -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
```

Set at least:

- `INTERNAL_SERVICE_TOKEN`
- `LLM_PROVIDER=openai`
- `LLM_MODEL=gpt-4o-mini`
- `OPENAI_API_KEY=<your_openai_api_key>` (or `LLM_API_KEY`)
- `MOCK_MODE=false`

For FactChat (OpenAI-compatible) usage:

- `LLM_PROVIDER=factchat`
- `FACTCHAT_API_KEY=<your_factchat_key>` (or `LLM_API_KEY`)
- `LLM_BASE_URL=<factchat_openai_compatible_base_url>`
- `LLM_MODEL=<factchat_model_name>`
- `MOCK_MODE=false`
- `PROMPT_VERSION=rec-comment-v1.2.0`
- `PLAN_PROMPT_VERSION=plan-v1.0.0`

## 2. Run

```powershell
python -m uvicorn app.main:app --host 0.0.0.0 --port 8001 --reload
```

Health check:

```bash
curl http://localhost:8001/health
```

## 3. Endpoint test (success)

```bash
curl -X POST "http://localhost:8001/v1/recommendation/comment" ^
  -H "Content-Type: application/json" ^
  -H "Authorization: Bearer replace-with-internal-token" ^
  -H "X-Request-Id: 11111111-1111-1111-1111-111111111111" ^
  -d "{ \"sessionId\": 1024, \"profile\": { \"mathLogicalScore\": 78, \"problemSolvingScore\": 82, \"infoTechUtilizationScore\": 74, \"softwareImplementationScore\": 85, \"systemUnderstandingScore\": 69, \"dataAnalysisScore\": 72, \"communicationScore\": 66, \"collaborationScore\": 70, \"selfManagementScore\": 76 }, \"topMajors\": [ { \"majorName\": \"Computer Science\", \"rankingOrder\": 1, \"fitScore\": 88.4, \"strengths\": \"problem solving\", \"weaknesses\": \"communication\" }, { \"majorName\": \"Data Science\", \"rankingOrder\": 2, \"fitScore\": 84.1 } ], \"userContext\": { \"grade\": \"11\", \"careerField\": \"AI\", \"preferredSubject\": \"Math\", \"studyHours\": 18 } }"
```

## 4. Endpoint test (validation fail -> 422)

```bash
curl -X POST "http://localhost:8001/v1/recommendation/comment" ^
  -H "Content-Type: application/json" ^
  -H "Authorization: Bearer replace-with-internal-token" ^
  -d "{ \"sessionId\": 1, \"profile\": { \"mathLogicalScore\": 101, \"problemSolvingScore\": 80, \"infoTechUtilizationScore\": 70, \"softwareImplementationScore\": 75, \"systemUnderstandingScore\": 60, \"dataAnalysisScore\": 65, \"communicationScore\": 55, \"collaborationScore\": 58, \"selfManagementScore\": 62 }, \"topMajors\": [ { \"majorName\": \"Computer Science\", \"rankingOrder\": 1, \"fitScore\": 90 } ] }"
```

## 5. Notes

- If `OPENAI_API_KEY` and `LLM_API_KEY` are both empty, service returns schema-valid mock output.
- If `FACTCHAT_API_KEY` is set, it can also be used as API key source.
- Real LLM path uses structured output to enforce response shape.

## 6. Weekly Plan Endpoint Test

```bash
curl -X POST "http://localhost:8001/v1/plan/weekly" ^
  -H "Content-Type: application/json" ^
  -H "Authorization: Bearer replace-with-internal-token" ^
  -H "X-Request-Id: 22222222-2222-2222-2222-222222222222" ^
  -d "{ \"sessionId\": 1024, \"targetMajor\": { \"majorName\": \"Computer Science\", \"fitScore\": 88.4 }, \"weaknessFocus\": [\"communicationScore\", \"systemUnderstandingScore\"], \"profile\": { \"mathLogicalScore\": 78, \"problemSolvingScore\": 82, \"infoTechUtilizationScore\": 74, \"softwareImplementationScore\": 85, \"systemUnderstandingScore\": 69, \"dataAnalysisScore\": 72, \"communicationScore\": 66, \"collaborationScore\": 70, \"selfManagementScore\": 76 }, \"constraints\": { \"weeks\": 6, \"studyHoursPerWeek\": 8, \"preferredStyle\": \"practice-first\" } }"
```

## 7. Quality Regression Test

```powershell
python -m pytest -q tests/test_quality_contract.py
python -m pytest -q tests/test_weekly_plan_contract.py
```

## 8. RAG Ingestion With OpenAI Large Embeddings

The project can ingest major/RAG dataset chunks into PostgreSQL + pgvector.

For higher-quality paid embeddings without changing the current `vector(1536)` schema, use OpenAI `text-embedding-3-large` with `dimensions=1536`.

Required environment variable:

- `EMBEDDING_API_KEY=<your_openai_api_key>` or `OPENAI_API_KEY=<your_openai_api_key>`

The helper script sets:

- `EMBEDDING_PROVIDER=openai-compatible`
- `EMBEDDING_MODEL=text-embedding-3-large`
- `EMBEDDING_DIMENSION=1536`
- `EMBEDDING_OUTPUT_DIMENSIONS=1536`
- `AI_RAG_VECTOR_SEARCH_ENABLED=true`

Dry run:

```powershell
cd C:\proj\JO\JOBE\ai-service
.\scripts\ingest_major_rag_openai_large.ps1 -DatasetVersion local-openai-large-v1 -DryRun -Limit 3
```

Actual ingestion:

```powershell
cd C:\proj\JO\JOBE\ai-service
.\scripts\ingest_major_rag_openai_large.ps1 -DatasetVersion local-openai-large-v1 -Force
```

Search test:

```powershell
cd C:\proj\JO\JOBE\ai-service
.\scripts\search_major_rag_openai_large.ps1 -MajorName "컴퓨터공학과" -DatasetVersion local-openai-large-v1
```

Do not commit `.env` files or API keys.
