"""New-generation presentation checks and offline hints, never semantic approval.

Reference membership and a few observable presentation checks are deterministic.
Topic wording checks are deliberately hints: passing them cannot prove grounding,
feasibility or safety. No review record or generation job is changed here.
"""
import re
from app.exploration_contract import Plan12Result


def presentation_issues(week) -> list[str]:
    """Deterministic requirements for NEW prompt output only; never rewrite stored plans."""
    codes = []
    first = week.tasks[0] if week.tasks else ""
    second = week.tasks[1] if len(week.tasks) > 1 else ""
    if len(week.tasks) != 2:
        codes.append("TWO_TASKS_REQUIRED")
    if not (re.search(r"준비(?:물)?\s*[:：]", first) and
            re.search(r"시간\s*[:：].*?\d+\s*(?:분|시간)", first) and "혼자" in first):
        codes.append("PREPARATION_TIME_OR_SOLO_MISSING")
    if not re.search(r"탐색\s*제안\s*[:：]", second):
        codes.append("AUTHORED_TASK_NOT_LABELLED")
    if not re.search(r"쉬운\s*대안\s*[:：]", second):
        codes.append("EASIER_ALTERNATIVE_MISSING")
    return codes


def assess_plan(result: dict, facts: list[dict]) -> dict:
    plan = Plan12Result.model_validate(result)
    references = {f["referenceKey"] for f in facts}
    evidence_text = " ".join(f["text"] for f in facts)
    findings = []
    for week in plan.weeks:
        text = " ".join([week.title, *week.tasks, week.expectedOutput])
        codes = presentation_issues(week)
        if not set(week.referenceKeys) <= references:
            codes.append("UNKNOWN_REFERENCE")
        if any(term in text and term not in evidence_text for term in ("취업", "전망", "직업", "연봉")):
            codes.append("CAREER_TOPIC_SCOPE_REVIEW")
        if re.search(r"졸업.*진로|진로.*졸업", text) and "졸업" not in evidence_text:
            codes.append("OUTCOME_TOPIC_SCOPE_REVIEW")
        if re.search(r"팀원.*모집|스터디.*(?:만들|구성)|모임.*만들", text):
            codes.append("COLLABORATOR_ASSUMPTION_REVIEW")
        if re.search(r"운영체제.*실습|프로그래밍.*공부|프로젝트.*실습", text):
            codes.append("TASK_SIZE_REVIEW")
        if codes:
            findings.append({"week": week.week, "codes": codes})
    return {"reviewVersion": "PLAN12_REVIEW_HINTS_V1_0", "schemaValid": True,
            "status": "REVIEW_HINTS_FOUND" if findings else "HUMAN_REVIEW_REQUIRED",
            "findings": findings, "semanticSupportVerified": False,
            "humanReviewRequired": True, "generationAuthorized": False,
            "limitation": "Wording hints can miss problems or flag optional/negated text. Reference membership is not source entailment."}
