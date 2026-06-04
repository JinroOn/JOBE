from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


MatchedBy = Literal["exact", "alias", "vector", "fallback", "none"]


class RetrievalRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    majorName: str = Field(min_length=1, max_length=100)
    weaknessFocus: list[str] = Field(default_factory=list, max_length=5)
    competencyProfile: dict[str, int | float | str | None] = Field(default_factory=dict)
    recommendationGroups: list[dict[str, Any]] = Field(default_factory=list, max_length=5)
    topMajors: list[str] = Field(default_factory=list, max_length=5)
    requestId: str | None = Field(default=None, max_length=120)
    datasetVersion: str | None = Field(default=None, max_length=80)
    topK: int = Field(default=5, ge=1, le=10)
    scoreThreshold: float = Field(default=0.3, ge=0, le=1)
    maxSnippetChars: int = Field(default=1600, ge=200, le=3000)
    logRetrieval: bool = False
    enableVectorSearch: bool = True


class RagSnippet(BaseModel):
    model_config = ConfigDict(extra="forbid")

    chunkId: int
    documentId: int
    majorName: str
    chunkText: str
    chunkType: str
    sourceType: str
    sourcePath: str | None = None
    score: float | None = None
    matchedBy: MatchedBy
    metadata: dict[str, Any] = Field(default_factory=dict)


class RetrievedMajorContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    majorName: str
    standardMajorName: str | None = None
    category: str | None = None
    description: str | None = None
    sourceSummary: str | None = None
    relatedJobs: list[str] = Field(default_factory=list)
    coreAxes: dict[str, Any] | list[Any] | None = None
    ragSnippets: list[str] = Field(default_factory=list)


class RetrievalMeta(BaseModel):
    model_config = ConfigDict(extra="forbid")

    matchedBy: MatchedBy
    topK: int
    scoreThreshold: float
    fallbackUsed: bool
    datasetVersion: str | None = None
    matchedChunkIds: list[int] = Field(default_factory=list)
    latencyMs: int | None = None
    failureReason: str | None = None
    aliasName: str | None = None
    canonicalMajorName: str | None = None


class RetrievalResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    majorContext: RetrievedMajorContext
    ragSnippets: list[RagSnippet] = Field(default_factory=list)
    retrievalMeta: RetrievalMeta
