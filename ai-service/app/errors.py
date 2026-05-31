from __future__ import annotations

import json
from dataclasses import dataclass

from fastapi import status
from fastapi.responses import JSONResponse

from .models import CommonErrorResponse


@dataclass
class AppError(Exception):
    status_code: int
    code: str
    message: str
    retryable: bool = False


class RequestTimeoutError(AppError):
    def __init__(self, message: str = "LLM request timed out") -> None:
        super().__init__(
            status_code=status.HTTP_408_REQUEST_TIMEOUT,
            code="REQUEST_TIMEOUT",
            message=message,
            retryable=True,
        )


class UpstreamUnavailableError(AppError):
    def __init__(self, message: str = "Upstream LLM unavailable") -> None:
        super().__init__(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            code="UPSTREAM_UNAVAILABLE",
            message=message,
            retryable=True,
        )


class RateLimitError(AppError):
    def __init__(self, message: str = "Rate limit exceeded") -> None:
        super().__init__(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            code="RATE_LIMIT_EXCEEDED",
            message=message,
            retryable=True,
        )


class UnauthorizedError(AppError):
    def __init__(self, message: str = "Unauthorized") -> None:
        super().__init__(
            status_code=status.HTTP_401_UNAUTHORIZED,
            code="UNAUTHORIZED",
            message=message,
            retryable=False,
        )


def build_error_response(
    *,
    status_code: int,
    code: str,
    message: str,
    request_id: str,
    retryable: bool,
) -> JSONResponse:
    body = CommonErrorResponse(
        code=code,
        message=message,
        requestId=request_id,
        retryable=retryable,
    )
    return JSONResponse(status_code=status_code, content=json.loads(body.model_dump_json()))

