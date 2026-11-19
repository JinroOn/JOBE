"""Combine per-major DEV review drafts for the existing activity catalog loader."""

from __future__ import annotations

import argparse
from hashlib import sha256
import json
from pathlib import Path
import re
import sys

REPO = Path(__file__).resolve().parents[2]
DRAFTS = REPO / "datasets/activities/major-review"
STATES = {
    "content_status": "DRAFT",
    "implementation_review_status": "PENDING",
    "domain_review_status": "PENDING",
}
KINDS = {"INFORMATION", "SMALL_TASK", "REFLECTION_AND_NEXT_STEP"}


def build_bundle(version: str, paths: list[Path], mapping: dict) -> dict:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,99}", version):
        raise ValueError("INVALID_BUNDLE_VERSION")
    if len(paths) < 2:
        raise ValueError("MULTIPLE_ACTIVITY_DRAFTS_REQUIRED")
    mapping_version = mapping["major_mapping_version"]
    expected = {row["major_code"]: row["major_name"] for row in mapping["majors"]}
    major_codes = set()
    original_versions = set()
    activities = {}
    sources = []
    for candidate in sorted(paths, key=lambda path: path.name):
        path = candidate.resolve()
        if path.parent != DRAFTS or not path.is_file():
            raise ValueError("ACTIVITY_DRAFT_PATH_REQUIRED")
        raw = path.read_bytes()
        catalog = json.loads(raw)
        original_version = catalog.get("version")
        if (catalog.get("schema_version") != "1.1"
                or catalog.get("major_mapping_version") != mapping_version
                or catalog.get("preference_policy_version") != "ACTIVITY_PREFERENCE_MAPPING_V1_0"
                or catalog.get("provenance", {}).get("scope") != "SINGLE_MAJOR_DEV_REVIEW"
                or any(catalog.get(field) != value for field, value in STATES.items())
                or not isinstance(original_version, str) or not original_version or original_version == version
                or original_version in original_versions):
            raise ValueError("INCOMPATIBLE_ACTIVITY_DRAFT")
        original_versions.add(original_version)
        items = catalog["activities"]
        if len(items) != 3 or {item["kind"] for item in items} != KINDS:
            raise ValueError("THREE_ACTIVITY_KINDS_REQUIRED")
        code = items[0]["major_code"]
        if code not in expected or code in major_codes:
            raise ValueError("DUPLICATE_OR_UNKNOWN_ACTIVITY_MAJOR")
        major_codes.add(code)
        for item in items:
            activity_id = item["activity_id"]
            if (item["major_code"] != code or item["major_name"] != expected[code]
                    or activity_id in activities or item.get("public_use_enabled") is not False
                    or item.get("changes_recommendation") is not False or item.get("score_effect") != "NONE"
                    or any(item.get(field) != value for field, value in STATES.items())):
                raise ValueError("INVALID_OR_DUPLICATE_ACTIVITY_DRAFT")
            activities[activity_id] = item
        sources.append({
            "path": path.relative_to(REPO).as_posix(),
            "version": original_version,
            "content_sha256": sha256(json.dumps(catalog, sort_keys=True, ensure_ascii=False,
                                                separators=(",", ":")).encode("utf-8")).hexdigest(),
        })
    return {
        "schema_version": "1.1",
        "version": version,
        "major_mapping_version": mapping_version,
        **STATES,
        "preference_policy_version": "ACTIVITY_PREFERENCE_MAPPING_V1_0",
        "provenance": {"scope": "MULTI_MAJOR_DEV_REVIEW", "source_catalogs": sources},
        "activities": list(activities.values()),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", action="append", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if output.is_relative_to(REPO) or output.exists() or not output.parent.is_dir():
        parser.error("NEW_EXTERNAL_OUTPUT_REQUIRED")
    try:
        mapping = json.loads((REPO / "datasets/exploration/major-mapping.json").read_text(encoding="utf-8"))
        bundle = build_bundle(args.version, args.catalog, mapping)
        with output.open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(bundle, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(f"Activity review bundle failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps({"version": args.version, "activities": len(bundle["activities"])}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
