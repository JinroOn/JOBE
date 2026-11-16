"""Authenticated preparation only; no provider, persistence, or approval creation."""
from contextlib import contextmanager
import os

from sqlalchemy import create_engine
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.db import get_ai_database_settings
from app.errors import AppError
from app.exploration_contract import ContractViolation, read_request
from app.rag.exploration_binding import (
    EvidenceIntegrityError, EvidenceNoLongerAvailable, EvidencePolicy, EvidencePolicyError,
    EvidenceStoreUnavailable, ExplorationEvidenceBinder,
)

VERSION = "EXPLORATION_EVIDENCE_TRANSPORT_V1_0"


@contextmanager
def evidence_session():
    engine = None
    try:
        engine = create_engine(get_ai_database_settings().sqlalchemy_url, connect_args={"connect_timeout": 3, "options": "-c statement_timeout=5000"})
        with Session(engine) as session:
            yield session
    except SQLAlchemyError:
        raise EvidenceStoreUnavailable() from None
    finally:
        if engine is not None:
            engine.dispose()


def prepare_evidence(payload: dict) -> dict:
    if os.getenv("EXPLORATION_EVIDENCE_ENABLED", "false").lower() != "true":
        raise AppError(503, "EVIDENCE_BINDING_DISABLED", "Evidence preparation is disabled")
    try:
        original = read_request(payload).model_dump()
        if original["evidence"]["bindingStatus"] != "NOT_BOUND":
            raise ContractViolation("Invalid exploration AI contract")
        policy = EvidencePolicy(os.getenv("EXPLORATION_EVIDENCE_DATASET_VERSION", ""))
        with evidence_session() as session:
            binder = ExplorationEvidenceBinder(session, policy)
            prepared = binder.prepare(original)
            if prepared.binding is not None:
                binder.revalidate(prepared.binding)
            return {"transportVersion": VERSION, "status": prepared.status,
                    "reasonCode": prepared.blocked_reason, "generationAuthorized": False,
                    "request": prepared.request}
    except ContractViolation:
        raise AppError(422, "EVIDENCE_CONTRACT_INVALID", "Invalid exploration evidence request") from None
    except EvidencePolicyError:
        raise AppError(503, "EVIDENCE_POLICY_INVALID", "Evidence policy is unavailable") from None
    except EvidenceIntegrityError:
        raise AppError(503, "EVIDENCE_INTEGRITY_ERROR", "Evidence integrity check failed") from None
    except EvidenceNoLongerAvailable:
        raise AppError(409, "EVIDENCE_NO_LONGER_AVAILABLE", "Evidence approval has changed") from None
    except EvidenceStoreUnavailable:
        raise AppError(503, "EVIDENCE_STORE_UNAVAILABLE", "Evidence store is unavailable", retryable=True) from None
