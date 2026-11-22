from copy import deepcopy
import json
from pathlib import Path
import pytest
from app.exploration_contract import ContractViolation, input_hash, read_request, validate_response, insufficient_evidence

CASES=json.loads((Path(__file__).resolve().parents[2]/"datasets/exploration-ai-contract/cases.json").read_text(encoding="utf-8"))
REQUESTS={case["name"]:case["request"] for case in CASES["requestCases"]}


@pytest.mark.parametrize("case",CASES["requestCases"],ids=lambda c:c["name"])
def test_shared_requests(case):
    if case["valid"]:
        assert read_request(case["request"]).inputHash==input_hash(case["request"])
    else:
        with pytest.raises(ContractViolation):read_request(case["request"])


@pytest.mark.parametrize("case",CASES["responseCases"],ids=lambda c:c["name"])
def test_shared_responses(case):
    request=REQUESTS[case["requestName"]]
    if case["valid"]:
        assert validate_response(request,case["response"]).requestId==request["requestId"]
    else:
        with pytest.raises(ContractViolation):validate_response(request,case["response"])


@pytest.mark.parametrize("case",CASES["adapterCases"],ids=lambda c:c["name"])
def test_java_expected_requests_use_same_canonical_unicode_hash(case):
    assert read_request(case["expectedRequest"]).inputHash==input_hash(case["expectedRequest"])


def test_insufficient_evidence_is_fixed_no_content_and_no_legacy_fallback():
    request=REQUESTS["consultation"]
    response=insufficient_evidence(request)
    assert validate_response(request,response).status=="INSUFFICIENT_EVIDENCE"
    assert response["result"] is None
    assert request["evidence"]["bindingStatus"]=="NOT_BOUND"


def test_validation_error_does_not_echo_private_body():
    request=deepcopy(REQUESTS["consultation"]);request["email"]="SYNTHETIC_PRIVATE_INPUT"
    with pytest.raises(ContractViolation) as error:read_request(request)
    assert "SYNTHETIC_PRIVATE_INPUT" not in str(error.value)
    response=insufficient_evidence(REQUESTS["consultation"]);response["result"]={"private":"SYNTHETIC_PRIVATE_OUTPUT"}
    with pytest.raises(ContractViolation) as error:validate_response(REQUESTS["consultation"],response)
    assert "SYNTHETIC_PRIVATE_OUTPUT" not in str(error.value)


def test_mutating_a_parsed_request_is_detected_on_revalidation():
    parsed=read_request(REQUESTS["consultation"])
    parsed.input.candidateNames.append("합성 변경")
    with pytest.raises(ContractViolation):read_request(parsed)
