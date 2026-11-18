from pathlib import Path
from types import SimpleNamespace
import json

import pytest

from scripts.prepare_rag_review import audit_document, major_code, source_path, source_urls
from app.rag.ingestion import parse_service_json


def fixture_document(tmp_path):
    path = tmp_path / "majors/major-row-133-example.service.json"
    path.parent.mkdir()
    path.write_text(json.dumps({"majorName": "Example", "standardMajorName": "Example",
                               "description": "A factual overview of the subjects in a university curriculum.",
                               "descriptionSources": [{"sourceUrl": "https://example.edu/curriculum"}],
                               "serviceCompetencyScores": {"privateLegacyScore": 99}}), encoding="utf-8")
    parsed = parse_service_json(path, dataset_root=tmp_path, dataset_version="fixed-v1")
    doc = SimpleNamespace(**{k: v for k, v in vars(parsed).items() if k != "chunks"}, id=1)
    chunks = [SimpleNamespace(**vars(c), id=index + 1) for index, c in enumerate(parsed.chunks)]
    return doc, chunks


def test_export_matches_local_content_without_exporting_legacy_metadata(tmp_path):
    doc, chunks = fixture_document(tmp_path)
    result = audit_document(doc, chunks, {"MAJOR_ROW_133": "Example"}, tmp_path)
    assert result["flags"] == []
    assert result["reviewStatus"] == "PENDING_HUMAN_REVIEW"
    assert result["sourceContentVerified"] is False
    assert result["localSourceUrls"] == ["https://example.edu/curriculum"]
    assert "privateLegacyScore" not in json.dumps(result)
    assert all(fact["chunkType"] in {"description", "major_overview", "related_jobs"} for fact in result["facts"])


def test_actual_text_drift_detected_even_when_declared_hash_unchanged(tmp_path):
    doc, chunks = fixture_document(tmp_path)
    chunks[0].chunk_text = "Tampered text retaining the original declared hash"
    result = audit_document(doc, chunks, {"MAJOR_ROW_133": "Example"}, tmp_path)
    assert "LOCAL_SOURCE_DRIFT" in result["flags"]


def test_canonical_rename_requires_review_and_no_automatic_alias(tmp_path):
    doc, chunks = fixture_document(tmp_path)
    result = audit_document(doc, chunks, {"MAJOR_ROW_133": "New Name"}, tmp_path)
    assert "CANONICAL_NAME_MISMATCH" in result["flags"]
    assert result["storedName"] == "Example"
    assert doc.major_name == "Example"


def test_wording_is_review_hint_not_approval_or_rejection(tmp_path):
    doc, chunks = fixture_document(tmp_path)
    next(c for c in chunks if c.chunk_type == "description").chunk_text = "논리적 사고력이 있는 학생에게 잘 맞을 수 있다."
    result = audit_document(doc, chunks, {"MAJOR_ROW_133": "Example"}, tmp_path)
    assert "FACT_TEXT_REVIEW_REQUIRED" in result["flags"]
    assert result["reviewStatus"] == "PENDING_HUMAN_REVIEW"


@pytest.mark.parametrize("relative", ["../secret.env", "../../other/data.json"])
def test_source_path_must_remain_inside_dataset(tmp_path, relative):
    with pytest.raises(ValueError, match="SOURCE_PATH_OUTSIDE_DATASETS"):
        source_path(tmp_path, relative)


def test_url_export_is_allowlisted_and_codes_do_not_match_arbitrary_digits():
    assert source_urls({"sourceUrl": "https://example.edu", "secret": "hidden", "otherUrl": "https://private"}) == ["https://example.edu"]
    assert major_code("majors/major-row-6-name.service.json") == "MAJOR_ROW_6"
    assert major_code("other-6.json") is None


def test_pilot_is_unapproved_and_cannot_claim_a_database_manifest():
    draft = json.loads((Path(__file__).resolve().parents[2] /
                        "datasets/exploration-rag-review/computer-science.pilot-review.json").read_text(encoding="utf-8"))
    assert draft["status"] == "PENDING_HUMAN_REVIEW"
    assert draft["reviewerId"] is None and draft["bindingManifest"] is None
    assert draft["generationAuthorized"] is False
    sources = {s["sourceId"] for s in draft["sources"]}
    for chunk in draft["chunks"]:
        assert chunk["chunkType"] in {"description", "major_overview"}
        assert 20 < len(chunk["text"]) <= 1600
        assert set(chunk["sourceIds"]) <= sources
