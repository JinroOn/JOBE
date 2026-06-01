from __future__ import annotations

import logging
import os
import time
import uuid
from collections import deque
from dataclasses import dataclass
from pathlib import Path
from threading import Lock

from fastapi import Depends, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from dotenv import load_dotenv

# Load ai-service/.env if present.
load_dotenv(dotenv_path=Path(__file__).resolve().parents[1] / ".env")

from .chain import RecommendationChain
from .chain_plan import WeeklyPlanChain
from .errors import AppError, RateLimitError, UnauthorizedError, build_error_response
from .models import (
    RecommendationCommentRequest,
    RecommendationCommentResponse,
    WeeklyPlanRequest,
    WeeklyPlanResponse,
)

app = FastAPI(title="MajorFit AI Service", version="1.0.0")
logger = logging.getLogger("majorfit.ai")
logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))


@dataclass
class RateLimitConfig:
    requests_per_minute: int = 60
    burst: int = 20


class InMemoryRateLimiter:
    def __init__(self, config: RateLimitConfig) -> None:
        self.config = config
        self.window = deque()
        self.lock = Lock()

    def check(self) -> None:
        now = time.time()
        one_minute_ago = now - 60
        with self.lock:
            while self.window and self.window[0] < one_minute_ago:
                self.window.popleft()
            if len(self.window) >= self.config.requests_per_minute:
                raise RateLimitError()
            current_second = sum(1 for ts in self.window if ts >= now - 1)
            if current_second >= self.config.burst:
                raise RateLimitError("Rate limit burst exceeded")
            self.window.append(now)


chain = RecommendationChain()
plan_chain = WeeklyPlanChain()
rate_limiter = InMemoryRateLimiter(
    RateLimitConfig(
        requests_per_minute=int(os.getenv("RATE_LIMIT_RPM", "60")),
        burst=int(os.getenv("RATE_LIMIT_BURST", "20")),
    )
)


@app.middleware("http")
async def request_context_middleware(request: Request, call_next):
    request.state.started_at = time.perf_counter()
    request_id_header = request.headers.get("X-Request-Id")
    request.state.request_id = resolve_request_id(request_id_header)
    return await call_next(request)


def resolve_request_id(x_request_id: str | None) -> str:
    return x_request_id.strip() if x_request_id and x_request_id.strip() else str(uuid.uuid4())


def require_internal_auth(authorization: str | None = Header(default=None)) -> None:
    expected_token = os.getenv("INTERNAL_SERVICE_TOKEN", "")
    if not authorization or not authorization.startswith("Bearer "):
        raise UnauthorizedError("Missing bearer token")
    token = authorization.split(" ", 1)[1].strip()
    if not expected_token:
        raise UnauthorizedError("Server internal token is not configured")
    if token != expected_token:
        raise UnauthorizedError("Invalid internal service token")


@app.exception_handler(AppError)
async def app_error_handler(request: Request, exc: AppError) -> JSONResponse:
    request_id = getattr(request.state, "request_id", str(uuid.uuid4()))
    started_at = getattr(request.state, "started_at", None)
    latency_ms = round((time.perf_counter() - started_at) * 1000, 2) if started_at else -1
    logger.warning(
        "requestId=%s status=%s code=%s retryable=%s latencyMs=%s path=%s",
        request_id,
        exc.status_code,
        exc.code,
        exc.retryable,
        latency_ms,
        request.url.path,
    )
    return build_error_response(
        status_code=exc.status_code,
        code=exc.code,
        message=exc.message,
        request_id=request_id,
        retryable=exc.retryable,
    )


@app.exception_handler(RequestValidationError)
async def request_validation_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    request_id = getattr(request.state, "request_id", str(uuid.uuid4()))
    started_at = getattr(request.state, "started_at", None)
    latency_ms = round((time.perf_counter() - started_at) * 1000, 2) if started_at else -1
    status_code = 422
    code = "VALIDATION_ERROR"
    retryable = False

    # malformed JSON is mapped to 400
    if any(error.get("type") == "json_invalid" for error in exc.errors()):
        status_code = 400
        code = "BAD_REQUEST"

    message = str(exc.errors()[0].get("msg", "Validation failed")) if exc.errors() else "Validation failed"
    logger.warning(
        "requestId=%s status=%s code=%s retryable=%s latencyMs=%s path=%s",
        request_id,
        status_code,
        code,
        False,
        latency_ms,
        request.url.path,
    )
    return build_error_response(
        status_code=status_code,
        code=code,
        message=message,
        request_id=request_id,
        retryable=retryable,
    )


@app.exception_handler(Exception)
async def unhandled_error_handler(request: Request, exc: Exception) -> JSONResponse:  # noqa: BLE001
    request_id = getattr(request.state, "request_id", str(uuid.uuid4()))
    started_at = getattr(request.state, "started_at", None)
    latency_ms = round((time.perf_counter() - started_at) * 1000, 2) if started_at else -1
    logger.exception(
        "requestId=%s status=500 code=INTERNAL_ERROR retryable=false latencyMs=%s path=%s",
        request_id,
        latency_ms,
        request.url.path,
    )
    return build_error_response(
        status_code=500,
        code="INTERNAL_ERROR",
        message="Internal server error",
        request_id=request_id,
        retryable=False,
    )


@app.post("/v1/recommendation/comment", response_model=RecommendationCommentResponse)
async def generate_recommendation_comment(
    payload: RecommendationCommentRequest,
    request: Request,
    x_request_id: str | None = Header(default=None, alias="X-Request-Id"),
    _auth: None = Depends(require_internal_auth),
) -> RecommendationCommentResponse:
    start = getattr(request.state, "started_at", time.perf_counter())
    request_id = getattr(request.state, "request_id", resolve_request_id(x_request_id))
    request.state.request_id = request_id

    rate_limiter.check()

    chain_result = await chain.generate_with_meta(payload, request_id=request_id)
    response = chain_result.response
    elapsed_ms = round((time.perf_counter() - start) * 1000, 2)
    fallback_reason = ",".join(chain_result.fallback_reasons) if chain_result.fallback_reasons else "-"
    logger.info(
        "requestId=%s sessionId=%s status=200 promptVersion=%s model=%s latencyMs=%s majorCount=%s fallback_reason=%s",
        request_id,
        payload.sessionId,
        chain_result.prompt_version,
        chain_result.model,
        elapsed_ms,
        len(payload.topMajors),
        fallback_reason,
    )
    return response


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/plan/weekly", response_model=WeeklyPlanResponse)
async def generate_weekly_plan(
    payload: WeeklyPlanRequest,
    request: Request,
    x_request_id: str | None = Header(default=None, alias="X-Request-Id"),
    _auth: None = Depends(require_internal_auth),
) -> WeeklyPlanResponse:
    start = getattr(request.state, "started_at", time.perf_counter())
    request_id = getattr(request.state, "request_id", resolve_request_id(x_request_id))
    request.state.request_id = request_id

    rate_limiter.check()

    chain_result = await plan_chain.generate_with_meta(payload, request_id=request_id)
    response = chain_result.response
    elapsed_ms = round((time.perf_counter() - start) * 1000, 2)
    fallback_reason = ",".join(chain_result.fallback_reasons) if chain_result.fallback_reasons else "-"
    logger.info(
        "requestId=%s sessionId=%s status=200 promptVersion=%s model=%s latencyMs=%s weeks=%s fallback_reason=%s",
        request_id,
        payload.sessionId,
        chain_result.prompt_version,
        chain_result.model,
        elapsed_ms,
        payload.constraints.weeks,
        fallback_reason,
    )
    return response
