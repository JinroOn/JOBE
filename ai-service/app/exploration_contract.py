"""New exploration wire contract. No route, retriever, provider call or approval creation.

BOUND means references declared by a trusted producer, not database authorization.
The current Java adapter only emits NOT_BOUND and cannot accept generation success.
"""
from __future__ import annotations

import hashlib
import json
from typing import Annotated, Literal

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, StrictBool, StringConstraints, model_validator

VERSION = "EXPLORATION_AI_CONTRACT_V1_0"
TOPICS = {
    "READINESS-INFO-01", "READINESS-CMP-01", "READINESS-SELF-01", "READINESS-ACT-02",
    "READINESS-ACT-01", "READINESS-CON-01", "READINESS-INFO-02", "READINESS-SELF-02",
    "ACTIVITY-REPORT-01", "ACTIVITY-PROJECT-01", "ACTIVITY-READ-01",
    "ACTIVITY-EXPERIMENT-01", "ACTIVITY-PRESENT-01", "ACTIVITY-CONTENT-01",
}


def nonblank(value: str) -> str:
    if not value.strip():
        raise ValueError("Blank text is not allowed")
    value.encode("utf-8")
    return value


def text(size: int):
    return Annotated[str, StringConstraints(min_length=1, max_length=size), AfterValidator(nonblank)]


Hash = Annotated[str, StringConstraints(pattern=r"^[a-f0-9]{64}$")]
Reference = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_-]{1,80}$")]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Uncertainty(StrictModel):
    lowEvidence: StrictBool
    interestTie: StrictBool
    lowDifferentiation: StrictBool


class Preference(StrictModel):
    topic: str
    meaning: Annotated[str, StringConstraints(pattern=r"^[A-Z][A-Z0-9_]{0,99}$")]
    unknown: StrictBool

    @model_validator(mode="after")
    def known_topic(self):
        if self.topic not in TOPICS:
            raise ValueError("Unknown preference topic")
        return self


class Collection(StrictModel):
    initialized: StrictBool
    applicableAnswerCount: Annotated[int, Field(ge=0, le=8)]
    budgetLimited: StrictBool
    omittedQuestionCount: Annotated[int, Field(ge=0, le=8)]


class ModelInput(StrictModel):
    diagnosisStatus: Literal["READY", "LOW_EVIDENCE", "NO_SUPPORTED_CANDIDATE"]
    candidateNames: Annotated[list[text(100)], Field(max_length=8)]
    targetName: text(100) | None
    uncertainty: Uncertainty
    preferences: Annotated[list[Preference], Field(max_length=8)]
    collection: Collection

    @model_validator(mode="after")
    def consistency(self):
        if len(set(self.candidateNames)) != len(self.candidateNames):
            raise ValueError("Duplicate candidate")
        if self.targetName is not None and self.targetName not in self.candidateNames:
            raise ValueError("Target is not a candidate")
        if self.diagnosisStatus == "NO_SUPPORTED_CANDIDATE":
            if self.candidateNames or self.targetName is not None:
                raise ValueError("No-candidate result cannot add candidates")
        elif not self.candidateNames:
            raise ValueError("Missing candidates")
        if self.uncertainty.lowEvidence != (self.diagnosisStatus == "LOW_EVIDENCE"):
            raise ValueError("Evidence status mismatch")
        if len({p.topic for p in self.preferences}) != len(self.preferences):
            raise ValueError("Duplicate preference topic")
        if self.collection.applicableAnswerCount != len(self.preferences):
            raise ValueError("Preference count mismatch")
        if not self.collection.initialized and (
            self.preferences or self.collection.budgetLimited or self.collection.omittedQuestionCount != 0
        ):
            raise ValueError("Uninitialized collection cannot imply answers")
        return self


class Evidence(StrictModel):
    approvedOnly: StrictBool
    bindingStatus: Literal["NOT_BOUND", "BOUND"]
    datasetVersion: text(100) | None
    referenceKeys: Annotated[list[Reference], Field(max_length=10)]

    @model_validator(mode="after")
    def declared_binding(self):
        if not self.approvedOnly or len(set(self.referenceKeys)) != len(self.referenceKeys):
            raise ValueError("Invalid evidence policy")
        if self.bindingStatus == "NOT_BOUND":
            if self.datasetVersion is not None or self.referenceKeys:
                raise ValueError("Unbound evidence cannot imply references")
        elif self.datasetVersion is None or not self.referenceKeys:
            raise ValueError("Bound evidence needs fixed version and references")
        return self


def input_hash(request: dict) -> str:
    body = {key: request[key] for key in ("sourceContextHash", "purpose", "input", "evidence")}
    raw = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


class ExplorationRequest(StrictModel):
    contractVersion: Literal["EXPLORATION_AI_CONTRACT_V1_0"]
    requestId: Reference
    sourceContextHash: Hash
    inputHash: Hash
    purpose: Literal["CONSULTATION", "PLAN"]
    mode: Literal["DEV"]
    testOnly: StrictBool
    input: ModelInput
    evidence: Evidence

    @model_validator(mode="after")
    def binding(self):
        if not self.testOnly:
            raise ValueError("Only isolated DEV is supported")
        if self.purpose == "PLAN" and (
            self.input.targetName is None or self.input.diagnosisStatus == "NO_SUPPORTED_CANDIDATE"
        ):
            raise ValueError("Plan requires a candidate target")
        if input_hash(self.model_dump()) != self.inputHash:
            raise ValueError("Input binding mismatch")
        return self


References = Annotated[list[Reference], Field(min_length=1, max_length=10)]


class ConsultationResult(StrictModel):
    summary: text(4000)
    questions: Annotated[list[text(300)], Field(max_length=3)]
    referenceKeys: References


class PlanStep(StrictModel):
    title: text(200)
    description: text(600)
    referenceKeys: References


class PlanResult(StrictModel):
    targetName: text(100)
    overview: text(2000)
    steps: Annotated[list[PlanStep], Field(min_length=1, max_length=6)]


class ExplorationResponse(StrictModel):
    contractVersion: Literal["EXPLORATION_AI_CONTRACT_V1_0"]
    requestId: Reference
    inputHash: Hash
    purpose: Literal["CONSULTATION", "PLAN"]
    status: Literal["INSUFFICIENT_EVIDENCE", "DECLINED", "SUCCEEDED"]
    changesRecommendation: StrictBool
    reasonCode: str | None
    result: dict | None


class ContractViolation(ValueError):
    """Fixed error text; callers must not log raw bodies or validation inputs."""


def read_request(value: dict | ExplorationRequest) -> ExplorationRequest:
    try:
        return ExplorationRequest.model_validate(value.model_dump() if isinstance(value, ExplorationRequest) else value)
    except (ValueError, TypeError):
        raise ContractViolation("Invalid exploration AI contract") from None


def validate_response(request: dict | ExplorationRequest, response: dict) -> ExplorationResponse:
    try:
        req = read_request(request)
        result = ExplorationResponse.model_validate(response)
        for key in ("contractVersion", "requestId", "inputHash", "purpose"):
            if getattr(req, key) != getattr(result, key):
                raise ValueError("Response binding mismatch")
        if result.changesRecommendation:
            raise ValueError("Recommendation must not change")
        if result.status == "INSUFFICIENT_EVIDENCE":
            if result.reasonCode != "APPROVED_EVIDENCE_UNAVAILABLE" or result.result is not None:
                raise ValueError("Invalid insufficient-evidence response")
        elif result.status == "DECLINED":
            if result.reasonCode not in {"OUT_OF_SCOPE", "POLICY_RESTRICTION"} or result.result is not None:
                raise ValueError("Invalid declined response")
        else:
            if req.evidence.bindingStatus != "BOUND" or result.reasonCode is not None:
                raise ValueError("No approved reference binding")
            if req.purpose == "CONSULTATION":
                content = ConsultationResult.model_validate(result.result)
                references = [content.referenceKeys]
            else:
                content = PlanResult.model_validate(result.result)
                if content.targetName != req.input.targetName:
                    raise ValueError("Plan target mismatch")
                references = [step.referenceKeys for step in content.steps]
            for refs in references:
                if len(set(refs)) != len(refs) or not set(refs).issubset(req.evidence.referenceKeys):
                    raise ValueError("Unknown or duplicate reference")
        return result
    except (ValueError, TypeError):
        raise ContractViolation("Invalid exploration AI contract") from None


def insufficient_evidence(request: dict | ExplorationRequest) -> dict:
    req = read_request(request)
    return {
        **{key: getattr(req, key) for key in ("contractVersion", "requestId", "inputHash", "purpose")},
        "status": "INSUFFICIENT_EVIDENCE", "changesRecommendation": False,
        "reasonCode": "APPROVED_EVIDENCE_UNAVAILABLE", "result": None,
    }
