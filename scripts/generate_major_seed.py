from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DATASET_DIR = ROOT / "datasets" / "majors"
INIT_SQL = ROOT / "init.sql"

START_MARKER = "-- BEGIN GENERATED MAJOR SEED"
END_MARKER = "-- END GENERATED MAJOR SEED"

COMPETENCY_FIELDS = [
    ("mathLogicalScore", "req_math_logic"),
    ("problemSolvingScore", "req_problem_solving"),
    ("infoTechUtilizationScore", "req_info_tech"),
    ("softwareImplementationScore", "req_implementation"),
    ("systemUnderstandingScore", "req_system_understanding"),
    ("dataAnalysisScore", "req_data_analysis"),
    ("communicationScore", "req_communication"),
    ("collaborationScore", "req_collaboration"),
    ("selfManagementScore", "req_self_management"),
]

TENDENCY_FIELDS = [
    ("logicalInquiryScore", "tend_logical_inquiry"),
    ("practicalTechnicalScore", "tend_practical_tech"),
    ("artCreativeScore", "tend_art_creative"),
    ("socialCooperationScore", "tend_social_cooperation"),
    ("bioHealthScore", "tend_life_health"),
    ("educationGuidanceScore", "tend_education_guide"),
    ("theoryAcademicScore", "tend_theory_academic"),
    ("dataAnalysisTypeScore", "tend_data_analytics"),
    ("systemOperationScore", "tend_system_operation"),
]

HIGH_LOAD_KEYWORDS = (
    "의학",
    "간호",
    "약학",
    "공학",
    "컴퓨터",
    "소프트웨어",
    "인공지능",
    "데이터",
    "통계",
    "수학",
    "물리",
    "화학",
    "생명",
)


def row_number(path: Path, data: dict[str, Any]) -> int:
    if isinstance(data.get("excelRowNumber"), int):
        return data["excelRowNumber"]
    match = re.search(r"major-row-(\d+)-", path.name)
    return int(match.group(1)) if match else 999999


def sql_string(value: Any) -> str:
    if value is None:
        return "NULL"
    text = str(value).replace("\\", "\\\\").replace("'", "''")
    return f"'{text}'"


def number(value: Any) -> str:
    if value is None:
        return "0"
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return "0"
    if parsed.is_integer():
        return str(int(parsed))
    return f"{parsed:.4f}".rstrip("0").rstrip(".")


def difficulty(data: dict[str, Any]) -> str:
    scores = data.get("serviceCompetencyScores") or {}
    name_text = f"{data.get('majorName', '')} {data.get('category', '')}"
    total = sum(float(scores.get(key, 0) or 0) for key, _ in COMPETENCY_FIELDS)
    if any(keyword in name_text for keyword in HIGH_LOAD_KEYWORDS) or total >= 105:
        return "high"
    if total <= 80:
        return "low"
    return "mid"


def career_paths(data: dict[str, Any]) -> str:
    occupations = data.get("relatedOccupations") or []
    names: list[str] = []
    for item in occupations:
        if not isinstance(item, dict):
            continue
        name = item.get("occupationName")
        if name and name not in names:
            names.append(str(name))
    return ",".join(names)


def build_rows() -> list[tuple[int, str]]:
    rows: list[tuple[int, str]] = []
    seen: set[str] = set()
    for path in DATASET_DIR.glob("*.service.json"):
        data = json.loads(path.read_text(encoding="utf-8"))
        if data.get("usableForRecommendation") is False:
            continue
        name = data.get("majorName") or data.get("standardMajorName")
        if not name or name in seen:
            continue
        seen.add(str(name))

        competency = data.get("serviceCompetencyScores") or {}
        tendency = data.get("serviceTendencyScores") or {}

        values: list[str] = [
            sql_string(name),
            sql_string(data.get("category") or "기타"),
            sql_string(difficulty(data)),
            sql_string(data.get("description") or ""),
            sql_string(career_paths(data)),
        ]
        values.extend(number(competency.get(source)) for source, _ in COMPETENCY_FIELDS)
        values.extend(number(tendency.get(source)) for source, _ in TENDENCY_FIELDS)
        values.extend(["0", "0", "NOW(6)", "NOW(6)"])

        rows.append((row_number(path, data), "    (" + ", ".join(values) + ")"))
    rows.sort(key=lambda item: item[0])
    return rows


def build_seed_block() -> str:
    rows = build_rows()
    if not rows:
        raise RuntimeError(f"No service major dataset files found under {DATASET_DIR}")

    row_sql = ",\n".join(f"-- {row_no}\n{row_sql}" for row_no, row_sql in rows)
    return f"""{START_MARKER}
INSERT INTO majors
    (name, category, difficulty, description, career_paths,
     req_math_logic, req_problem_solving, req_info_tech, req_implementation, req_system_understanding,
     req_data_analysis, req_communication, req_collaboration, req_self_management,
     tend_logical_inquiry, tend_practical_tech, tend_art_creative, tend_social_cooperation, tend_life_health,
     tend_education_guide, tend_theory_academic, tend_data_analytics, tend_system_operation,
     thr_math_logic, thr_info_tech, created_at, updated_at)
VALUES
{row_sql};
{END_MARKER}"""


def replace_seed_block() -> None:
    text = INIT_SQL.read_text(encoding="utf-8")
    block = build_seed_block()

    if START_MARKER in text and END_MARKER in text:
        pattern = re.compile(
            re.escape(START_MARKER) + r".*?" + re.escape(END_MARKER),
            re.DOTALL,
        )
        updated = pattern.sub(block, text)
    else:
        pattern = re.compile(
            r"INSERT INTO majors\s+.*?;\n\n-- ============================================================\n-- notices",
            re.DOTALL,
        )
        updated = pattern.sub(
            block + "\n\n-- ============================================================\n-- notices",
            text,
        )
        if updated == text:
            raise RuntimeError("Could not find majors INSERT block in init.sql")

    INIT_SQL.write_text(updated, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    replace_seed_block()
    print(f"Generated {len(build_rows())} major seed rows in {INIT_SQL}")
