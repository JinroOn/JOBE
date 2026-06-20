from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

WeaknessField = Literal[
    "mathLogicalScore",
    "problemSolvingScore",
    "infoTechUtilizationScore",
    "softwareImplementationScore",
    "systemUnderstandingScore",
    "dataAnalysisScore",
    "communicationScore",
    "collaborationScore",
    "selfManagementScore",
]


class Profile(BaseModel):
    model_config = ConfigDict(extra="forbid")

    mathLogicalScore: int = Field(ge=0, le=100)
    problemSolvingScore: int = Field(ge=0, le=100)
    infoTechUtilizationScore: int = Field(ge=0, le=100)
    softwareImplementationScore: int = Field(ge=0, le=100)
    systemUnderstandingScore: int = Field(ge=0, le=100)
    dataAnalysisScore: int = Field(ge=0, le=100)
    communicationScore: int = Field(ge=0, le=100)
    collaborationScore: int = Field(ge=0, le=100)
    selfManagementScore: int = Field(ge=0, le=100)


class MajorContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    category: str | None = Field(default=None, max_length=30)
    description: str | None = Field(default=None, max_length=1000)
    sourceSummary: str | None = Field(default=None, max_length=700)
    relatedJobs: list[str] = Field(default_factory=list, max_length=8)
    ragSnippets: list[str] = Field(default_factory=list, max_length=6)


class TopMajor(BaseModel):
    model_config = ConfigDict(extra="forbid")

    majorName: str = Field(min_length=1, max_length=100)
    rankingOrder: int = Field(ge=1, le=5)
    fitScore: float = Field(ge=0, le=100)
    strengths: str | None = Field(default=None, max_length=500)
    weaknesses: str | None = Field(default=None, max_length=500)
    majorContext: MajorContext | None = None


class DifferencePoint(BaseModel):
    model_config = ConfigDict(extra="forbid")

    majorName: str = Field(min_length=1, max_length=100)
    description: str = Field(min_length=1, max_length=500)


class RecommendationGroup(BaseModel):
    model_config = ConfigDict(extra="forbid")

    groupOrder: int = Field(ge=1, le=5)
    representativeMajorName: str = Field(min_length=1, max_length=100)
    representativeRankingOrder: int = Field(ge=1, le=5)
    similarMajorNames: list[str] = Field(default_factory=list, max_length=5)
    commonFitAxes: list[str] = Field(default_factory=list, max_length=5)
    differencePoints: list[DifferencePoint] = Field(default_factory=list, max_length=5)


class UserContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    grade: str | None = Field(default=None, max_length=30)
    careerField: str | None = Field(default=None, max_length=50)
    preferredSubject: str | None = Field(default=None, max_length=50)
    studyHours: int | None = Field(default=None, ge=0, le=168)
    learningStyle: str | None = Field(default=None, max_length=50)
    theoryPreference: int | None = Field(default=None, ge=0, le=100)
    practicePreference: int | None = Field(default=None, ge=0, le=100)
    mathAchievement: int | None = Field(default=None, ge=0, le=100)
    scienceAchievement: int | None = Field(default=None, ge=0, le=100)
    problemSolvingStyle: str | None = Field(default=None, max_length=100)


class RecommendationCommentRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionId: int = Field(ge=1)
    profile: Profile
    topMajors: list[TopMajor] = Field(min_length=1, max_length=5)
    recommendationGroups: list[RecommendationGroup] = Field(default_factory=list, max_length=5)
    userContext: UserContext | None = None

    @model_validator(mode="after")
    def validate_unique_ranking(self) -> "RecommendationCommentRequest":
        ranking_orders = [major.rankingOrder for major in self.topMajors]
        if len(set(ranking_orders)) != len(ranking_orders):
            raise ValueError("topMajors.rankingOrder must be unique")
        return self


class MajorComment(BaseModel):
    model_config = ConfigDict(extra="forbid")

    majorName: str = Field(min_length=1, max_length=100)
    rankingOrder: int = Field(ge=1, le=5)
    fitScore: float = Field(ge=0, le=100)
    strengths: str | None = Field(default=None, max_length=500)
    weaknesses: str | None = Field(default=None, max_length=500)
    recommendationReason: str = Field(min_length=1, max_length=800)


class RecommendationCommentResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    summaryComment: str = Field(min_length=1, max_length=1200)
    majorComments: list[MajorComment] = Field(min_length=1, max_length=5)
    weaknessFocus: list[WeaknessField] = Field(default_factory=list, max_length=2)
    version: str = Field(min_length=1, max_length=40)
    requestId: str = Field(min_length=1, max_length=100)


class CommonErrorResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    code: Literal[
        "BAD_REQUEST",
        "UNAUTHORIZED",
        "REQUEST_TIMEOUT",
        "VALIDATION_ERROR",
        "RATE_LIMIT_EXCEEDED",
        "INTERNAL_ERROR",
        "UPSTREAM_UNAVAILABLE",
    ]
    message: str = Field(min_length=1, max_length=500)
    requestId: str = Field(min_length=1, max_length=100)
    retryable: bool


class WeeklyPlanTargetMajor(BaseModel):
    model_config = ConfigDict(extra="forbid")

    majorName: str = Field(min_length=1, max_length=100)
    fitScore: float = Field(ge=0, le=100)
    majorContext: MajorContext | None = None


class WeeklyPlanConstraints(BaseModel):
    model_config = ConfigDict(extra="forbid")

    weeks: int = Field(ge=4, le=12)
    studyHoursPerWeek: int = Field(ge=1, le=40)
    preferredStyle: str | None = Field(default=None, max_length=100)


class WeeklyPlanRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionId: int = Field(ge=1)
    targetMajor: WeeklyPlanTargetMajor
    weaknessFocus: list[WeaknessField] = Field(min_length=1, max_length=2)
    profile: Profile
    constraints: WeeklyPlanConstraints


class WeeklyPlanItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    week: int = Field(ge=1, le=12)
    goal: str = Field(min_length=1, max_length=300)
    tasks: list[str] = Field(min_length=2, max_length=8)
    recommendedResources: list[str] = Field(min_length=1, max_length=8)
    checkpoint: str = Field(min_length=1, max_length=300)


class WeeklyPlanResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    planId: str = Field(min_length=1, max_length=100)
    version: str = Field(min_length=1, max_length=40)
    overview: str = Field(min_length=1, max_length=1200)
    weeklyPlan: list[WeeklyPlanItem] = Field(min_length=4, max_length=12)
    riskNotes: list[str] = Field(default_factory=list, max_length=10)
    requestId: str = Field(min_length=1, max_length=100)


class ConsultationHistoryMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=10000)


class ConsultationMajorContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    category: str | None = Field(default=None, max_length=50)
    description: str | None = Field(default=None, max_length=2000)
    careerPaths: str | None = Field(default=None, max_length=2000)
    requiredCompetencies: dict[str, float] = Field(default_factory=dict)


class ConsultationTopMajor(BaseModel):
    model_config = ConfigDict(extra="forbid")

    majorId: int | None = None
    majorName: str | None = Field(default=None, max_length=100)
    rank: int | None = Field(default=None, ge=1, le=20)
    finalScore: float | None = Field(default=None, ge=0, le=100)
    competencyScore: float | None = Field(default=None, ge=0, le=100)
    tendencyScore: float | None = Field(default=None, ge=0, le=100)
    failed: bool | None = None
    strengths: str | None = Field(default=None, max_length=1000)
    weaknesses: str | None = Field(default=None, max_length=1000)
    recommendationReason: str | None = Field(default=None, max_length=1500)
    majorContext: ConsultationMajorContext | None = None


class ConsultationPlanItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    weekNo: int | None = Field(default=None, ge=1, le=52)
    goal: str | None = Field(default=None, max_length=500)
    tasksJson: str | None = Field(default=None, max_length=3000)
    resourcesJson: str | None = Field(default=None, max_length=3000)
    checkpoint: str | None = Field(default=None, max_length=500)
    completed: bool | None = None


class ConsultationPlanContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    planId: int | None = None
    resultMajorScoreId: int | None = None
    overview: str | None = Field(default=None, max_length=1200)
    activeVersion: bool | None = None
    items: list[ConsultationPlanItem] = Field(default_factory=list, max_length=12)


class ConsultationDiagnosisContext(BaseModel):
    model_config = ConfigDict(extra="forbid")

    diagnosisResultId: int
    usedLatestDiagnosisResult: bool = False
    competencyVector: str | None = Field(default=None, max_length=5000)
    tendencyVector: str | None = Field(default=None, max_length=5000)
    aiComment: str | None = Field(default=None, max_length=3000)
    weaknessFocus: list[str] = Field(default_factory=list, max_length=9)
    topMajors: list[ConsultationTopMajor] = Field(default_factory=list, max_length=5)
    plans: list[ConsultationPlanContext] = Field(default_factory=list, max_length=3)


class ConsultationChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionId: int = Field(ge=1)
    userId: int = Field(ge=1)
    userMessage: str = Field(min_length=1, max_length=10000)
    history: list[ConsultationHistoryMessage] = Field(default_factory=list, max_length=20)
    hasDiagnosisContext: bool = False
    diagnosisContext: ConsultationDiagnosisContext | None = None

    @model_validator(mode="after")
    def normalize_context_flags(self) -> "ConsultationChatRequest":
        if self.diagnosisContext is not None:
            self.hasDiagnosisContext = True
        return self


class ConsultationChatResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    content: str = Field(min_length=1, max_length=2000)
    version: str = Field(min_length=1, max_length=40)
    requestId: str = Field(min_length=1, max_length=100)
