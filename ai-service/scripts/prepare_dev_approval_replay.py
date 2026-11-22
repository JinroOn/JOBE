"""Check the recorded DEV inputs and prepare portable approval inputs without writing to a DB."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.rag.ingestion import stable_hash
from scripts.prepare_activity_review_bundle import build_bundle
from scripts.review_rag_evidence import SCHEMA, validate_receipt
from scripts.stage_rag_review import payloads, validate_draft
from scripts.verify_rag_backup_migration import VerificationFault, require

SNAPSHOT = REPO / "datasets/dev-approvals/all94-20261120.json"


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def checked_path(relative, directory, expected_blob):
    path = (REPO / relative).resolve()
    require(path.parent == REPO / directory and path.is_file(), "APPROVAL_SOURCE_PATH_REQUIRED")
    blob = subprocess.check_output(
        ["git", "hash-object", "--path", relative, "--", relative], cwd=REPO, text=True
    ).strip()
    require(blob == expected_blob, f"APPROVAL_SOURCE_BLOB_CHANGED: {relative}")
    return path


def prepare(snapshot):
    require(snapshot["schemaVersion"] == "DEV_APPROVAL_SNAPSHOT_V1" and snapshot["scope"] == "DEV",
            "DEV_APPROVAL_SNAPSHOT_REQUIRED")
    mapping = read_json(REPO / "datasets/exploration/major-mapping.json")
    majors = snapshot["majors"]
    require(set(majors) == {row["major_code"] for row in mapping["majors"]}, "APPROVAL_MAJOR_SET_CHANGED")
    activity_paths, activity_hashes = [], {}
    for code, entry in majors.items():
        evidence = checked_path(entry["evidencePath"], "datasets/exploration-rag-review", entry["evidenceBlob"])
        draft = validate_draft(read_json(evidence), mapping)
        relative = evidence.relative_to(REPO / "datasets").as_posix()
        document, _ = payloads(dict(draft, proposedDatasetVersion=snapshot["ragVersion"]), relative)
        require(draft["majorCode"] == code and document["content_hash"] == entry["evidenceContentHash"],
                f"EVIDENCE_APPROVAL_HASH_CHANGED: {code}")
        activity_path = checked_path(entry["activityPath"], "datasets/activities/major-review", entry["activityBlob"])
        activity_paths.append(activity_path)
        activities = read_json(activity_path)["activities"]
        require({item["major_code"] for item in activities} == {code}
                and {item["activity_id"] for item in activities} == set(entry["activities"])
                and not activity_hashes.keys() & entry["activities"].keys(), f"APPROVAL_ACTIVITY_SET_CHANGED: {code}")
        activity_hashes.update(entry["activities"])
    catalog = build_bundle(snapshot["activityVersion"], activity_paths, mapping)
    require(stable_hash(catalog) == snapshot["activityCatalogHash"]
            and {activity["activity_id"]: stable_hash(activity) for activity in catalog["activities"]} == activity_hashes,
            "ACTIVITY_APPROVAL_HASH_CHANGED")
    return catalog, activity_hashes


def rag_receipts(snapshot, report):
    version = snapshot["ragVersion"]
    require(report["schemaVersion"] == "RAG_STAGED_REVIEW_BATCH_V1" and report["scope"] == "DEV"
            and report["datasetVersion"] == version and not report["generationAuthorized"],
            "STAGED_DEV_REPORT_REQUIRED")
    majors = snapshot["majors"]
    documents = report["documents"]
    require(len(documents) == len(majors) and {doc["majorCode"] for doc in documents} == set(majors)
            and len({doc["documentId"] for doc in documents}) == len(majors), "STAGED_MAJOR_SET_CHANGED")
    result = {}
    for doc in documents:
        code = doc["majorCode"]
        entry = majors[code]
        require(doc["datasetVersion"] == version and doc["scope"] == "DEV" and doc["bindingManifest"]
                and doc["contentHash"] == entry["evidenceContentHash"]
                and "datasets/" + doc["sourcePath"] == entry["evidencePath"],
                f"STAGED_DOCUMENT_CHANGED: {code}")
        receipt = dict(schemaVersion=SCHEMA, documentId=doc["documentId"], datasetVersion=version, scope="DEV",
                       contentHash=doc["contentHash"], bindingManifest=doc["bindingManifest"], expectedLatestReviewId=None,
                       decision="APPROVED", **{key: snapshot[key] for key in ("reviewerId", "reviewedAt", "sourceReference")})
        validate_receipt(receipt)
        result[code] = receipt
    return result


def write_json(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="New directory outside the repository")
    parser.add_argument("--staged-rag", type=Path, help="Batch report from stage_rag_review.py --apply")
    args = parser.parse_args()
    output = args.output.resolve()
    try:
        require(not output.is_relative_to(REPO) and not output.exists() and output.parent.is_dir(),
                "NEW_EXTERNAL_OUTPUT_REQUIRED")
        snapshot = read_json(SNAPSHOT)
        catalog, hashes = prepare(snapshot)
        receipts = rag_receipts(snapshot, read_json(args.staged_rag)) if args.staged_rag else {}
        output.mkdir()
        write_json(output / "activities.json", catalog)
        write_json(output / "activity-target-hashes.json", {
            "version": snapshot["activityVersion"], "catalogContentHash": snapshot["activityCatalogHash"], "activities": hashes,
        })
        for code, receipt in receipts.items():
            write_json(output / f"{code}.json", receipt)
        print(json.dumps({"scope": "DEV", "documents": len(snapshot["majors"]), "activities": len(hashes),
                          "ragReceipts": len(receipts)}))
    except (VerificationFault, ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"DEV approval replay preparation failed: {error}\n")


if __name__ == "__main__":
    main()
