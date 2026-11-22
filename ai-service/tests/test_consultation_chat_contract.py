from __future__ import annotations

import asyncio

from app.chain_consultation import ConsultationChain
from app.models import ConsultationChatRequest


def test_consultation_system_prompt_treats_braces_as_literal_text() -> None:
    chain = ConsultationChain()
    chain._system_prompt = lambda: 'Never expose "{userGoal}" as a template variable.'  # noqa: SLF001

    messages = chain._build_prompt().format_messages(input_json='{"message": "hi"}')  # noqa: SLF001

    assert messages[0].content == 'Never expose "{userGoal}" as a template variable.'
    assert messages[1].content == 'Input JSON:\n{"message": "hi"}'


def test_consultation_chat_mock_recommends_jinroon_diagnosis_when_context_is_missing(monkeypatch) -> None:
    monkeypatch.setenv("MOCK_MODE", "true")
    chain = ConsultationChain()
    request = ConsultationChatRequest(
        sessionId=1,
        userId=7,
        userMessage="내가 따면 좋을 자격증 추천해줘",
        history=[
            {
                "role": "user",
                "content": "내가 따면 좋을 자격증 추천해줘",
            }
        ],
        hasDiagnosisContext=False,
        diagnosisContext=None,
    )

    result = asyncio.run(chain.generate_with_meta(request=request, request_id="consultation-test-1"))

    assert result.response.requestId == "consultation-test-1"
    assert result.response.version == "consultation-chat-v1.0.0"
    assert "진로온" in result.response.content
    assert "전공 추천" in result.response.content
    assert "진단" in result.response.content
    assert "개인 맞춤" in result.response.content


def test_consultation_chat_mock_uses_existing_diagnosis_context(monkeypatch) -> None:
    monkeypatch.setenv("MOCK_MODE", "true")
    chain = ConsultationChain()
    request = ConsultationChatRequest.model_validate(
        {
            "sessionId": 1,
            "userId": 7,
            "userMessage": "내가 따면 좋을 자격증 추천해줘",
            "history": [
                {"role": "user", "content": "컴퓨터공학과 쪽으로 준비하고 싶어"},
                {"role": "user", "content": "내가 따면 좋을 자격증 추천해줘"},
            ],
            "hasDiagnosisContext": True,
            "diagnosisContext": {
                "diagnosisResultId": 5,
                "usedLatestDiagnosisResult": True,
                "competencyVector": "{}",
                "tendencyVector": "{}",
                "aiComment": "소프트웨어 구현 역량이 강점입니다.",
                "weaknessFocus": ["communicationScore"],
                "topMajors": [
                    {
                        "majorId": 100,
                        "majorName": "컴퓨터공학과",
                        "rank": 1,
                        "finalScore": 88.0,
                        "competencyScore": 90.0,
                        "tendencyScore": 85.0,
                        "failed": False,
                        "strengths": "구현 역량",
                        "weaknesses": "의사소통",
                        "recommendationReason": "소프트웨어 구현 역량과 잘 맞습니다.",
                        "majorContext": {
                            "category": "공학",
                            "description": "컴퓨터 시스템과 소프트웨어를 학습합니다.",
                            "careerPaths": "소프트웨어 개발자, 데이터 엔지니어",
                            "requiredCompetencies": {"softwareImplementationScore": 90.0},
                        },
                    }
                ],
                "plans": [],
            },
        }
    )

    result = asyncio.run(chain.generate_with_meta(request=request, request_id="consultation-test-2"))

    assert result.response.requestId == "consultation-test-2"
    assert "컴퓨터공학과" in result.response.content
    assert "구체적인 강의나 자격증 목록 데이터" in result.response.content
