from __future__ import annotations

import os
from dataclasses import dataclass
from urllib.parse import quote_plus


@dataclass(frozen=True)
class AiDatabaseSettings:
    url: str
    sqlalchemy_url: str
    healthcheck_enabled: bool


def _truthy(value: str | None) -> bool:
    return (value or "").strip().lower() in {"1", "true", "yes", "y", "on"}


def get_ai_database_settings() -> AiDatabaseSettings:
    explicit_url = os.getenv("AI_DATABASE_URL", "").strip()
    if explicit_url:
        sqlalchemy_url = _to_sqlalchemy_url(explicit_url)
        url = _to_psycopg_url(explicit_url)
    else:
        host = os.getenv("AI_DB_HOST", "localhost").strip()
        port = os.getenv("AI_DB_PORT", "5433").strip()
        name = os.getenv("AI_DB_NAME", "jobe_ai").strip()
        user = os.getenv("AI_DB_USER", "jobe_ai").strip()
        password = quote_plus(os.getenv("AI_DB_PASSWORD", "root"))
        url = f"postgresql://{user}:{password}@{host}:{port}/{name}"
        sqlalchemy_url = f"postgresql+psycopg://{user}:{password}@{host}:{port}/{name}"

    return AiDatabaseSettings(
        url=url,
        sqlalchemy_url=sqlalchemy_url,
        healthcheck_enabled=_truthy(os.getenv("AI_DB_HEALTHCHECK_ENABLED", "false")),
    )


def is_ai_db_healthcheck_enabled() -> bool:
    return get_ai_database_settings().healthcheck_enabled


def check_ai_db_health() -> str:
    """Return AI DB status without leaking connection details."""
    settings = get_ai_database_settings()
    try:
        import psycopg

        with psycopg.connect(settings.url, connect_timeout=3) as conn:
            with conn.cursor() as cursor:
                cursor.execute("SELECT 1")
                cursor.fetchone()
        return "ok"
    except Exception:  # noqa: BLE001
        return "unavailable"


def _to_sqlalchemy_url(url: str) -> str:
    if url.startswith("postgresql+psycopg://"):
        return url
    if url.startswith("postgresql://"):
        return "postgresql+psycopg://" + url.removeprefix("postgresql://")
    return url


def _to_psycopg_url(url: str) -> str:
    if url.startswith("postgresql+psycopg://"):
        return "postgresql://" + url.removeprefix("postgresql+psycopg://")
    return url
