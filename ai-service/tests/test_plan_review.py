from copy import deepcopy
import pytest
from pydantic import ValidationError
from app.plan_review import assess_plan

FACTS = [{"referenceKey": "ref_test", "text": "컴퓨터 시스템, 자료구조, 운영체제의 개념을 살펴본다."}]


def plan():
    return {"targetName": "컴퓨터공학과", "weeks": [
        {"week": n, "phase": "INFO" if n <= 4 else "BASIC_LEARNING" if n <= 8 else "SMALL_ACTIVITY",
         "title": "자료구조 탐색", "tasks": ["준비: 종이 | 시간: 20분 내외 | 방식: 혼자",
         "탐색 제안: 일상 목록을 세 칸 표로 정리하기. 쉬운 대안: 세 문장으로 적기."],
         "expectedOutput": "세 칸 표 또는 세 문장", "referenceKeys": ["ref_test"]} for n in range(1, 13)]}


def test_readable_plan_never_becomes_automatic_grounding_or_approval():
    value = plan()
    before = deepcopy(value)
    review = assess_plan(value, FACTS)
    assert review["findings"] == [] and review["status"] == "HUMAN_REVIEW_REQUIRED"
    assert review["semanticSupportVerified"] is False and review["generationAuthorized"] is False
    assert value == before


@pytest.mark.parametrize("week,title,tasks,code", [
    (4, "직업 세계", ["컴퓨터공학과 취업 전망 조사하기"], "CAREER_TOPIC_SCOPE_REVIEW"),
    (5, "프로그래밍 기초", ["기초 프로그래밍 공부하기", "간단한 프로젝트 실습하기"], "TASK_SIZE_REVIEW"),
    (8, "운영체제 기초", ["간단한 운영체제 실습하기"], "TASK_SIZE_REVIEW"),
    (10, "팀 프로젝트", ["팀원 모집 및 역할 분담하기"], "COLLABORATOR_ASSUMPTION_REVIEW"),
    (11, "스터디", ["스터디 그룹 만들기"], "COLLABORATOR_ASSUMPTION_REVIEW"),
])
def test_previous_provider_failure_patterns_are_review_hints(week, title, tasks, code):
    value = plan()
    value["weeks"][week - 1].update(title=title, tasks=tasks)
    findings = assess_plan(value, FACTS)["findings"]
    assert any(f["week"] == week and code in f["codes"] for f in findings)


def test_valid_reference_does_not_hide_missing_preparation_or_out_of_scope_topic():
    value = plan()
    value["weeks"][3]["tasks"] = ["취업 전망 조사하기"]
    finding = assess_plan(value, FACTS)["findings"][0]
    assert "UNKNOWN_REFERENCE" not in finding["codes"]
    assert {"CAREER_TOPIC_SCOPE_REVIEW", "PREPARATION_TIME_OR_SOLO_MISSING",
            "AUTHORED_TASK_NOT_LABELLED", "EASIER_ALTERNATIVE_MISSING"} <= set(finding["codes"])


def test_unknown_reference_is_distinct_from_semantic_review():
    value = plan()
    value["weeks"][0]["referenceKeys"] = ["ref_unknown"]
    assert "UNKNOWN_REFERENCE" in assess_plan(value, FACTS)["findings"][0]["codes"]


def test_graduate_career_topic_is_flagged_without_claiming_it_is_false():
    value=plan()
    value['weeks'][3]['tasks'][1]='탐색 제안: 졸업 후 진로 조사. 쉬운 대안: 제목 적기.'
    review=assess_plan(value,FACTS)
    assert 'OUTCOME_TOPIC_SCOPE_REVIEW' in review['findings'][0]['codes']
    assert review['semanticSupportVerified'] is False


def test_malformed_week_order_is_not_a_content_review_success():
    value = plan()
    value["weeks"][0]["week"] = 2
    with pytest.raises(ValidationError):
        assess_plan(value, FACTS)
