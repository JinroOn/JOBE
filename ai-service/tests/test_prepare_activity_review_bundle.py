import json

import pytest

from scripts import prepare_activity_review_bundle as workflow


def test_bundle_keeps_all_nine_source_activities_unreviewed_and_is_order_independent():
    mapping = json.loads((workflow.REPO / "datasets/exploration/major-mapping.json").read_text(encoding="utf-8"))
    paths = [workflow.DRAFTS / f"major-row-{row}.json" for row in (3, 40, 166)]
    version = "ACTIVITY_CATALOG_TEST_COMMON"
    bundle = workflow.build_bundle(version, paths, mapping)
    assert bundle == workflow.build_bundle(version, list(reversed(paths)), mapping)
    assert bundle["version"] == version and bundle["content_status"] == "DRAFT"
    assert len(bundle["provenance"]["source_catalogs"]) == 3
    assert len(bundle["activities"]) == 9
    originals = {item["activity_id"]: item for path in paths
                 for item in json.loads(path.read_text(encoding="utf-8"))["activities"]}
    assert {item["activity_id"]: item for item in bundle["activities"]} == originals
    with pytest.raises(ValueError, match="INCOMPATIBLE_ACTIVITY_DRAFT"):
        workflow.build_bundle(version, [paths[0], paths[0]], mapping)
