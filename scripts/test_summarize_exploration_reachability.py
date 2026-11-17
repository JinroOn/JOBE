"""Evidence-merger regressions. Synthetic files only; no database or network."""
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from summarize_exploration_reachability import summarize


class AuditMergeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.recipes = self.root / "recipes.json"
        self.mapping = self.root / "mapping.json"
        self.write(self.recipes, {"profiles": [{"id": "A:baseline", "target_major": "A"}, {"id": "B:baseline", "target_major": "B"}]})
        self.write(self.mapping, {"majors": [{"major_code": c, "major_name": c, "exploration_group": "G"} for c in "AB"]})
        self.dirs = [self.root / "part0", self.root / "part1"]
        for index, code in enumerate("AB"):
            directory = self.dirs[index]
            directory.mkdir()
            self.write(directory / "manifest.json", {
                "fixtureFileHash": hashlib.sha256(self.recipes.read_bytes()).hexdigest(), "harnessVersion": "v1",
                "harnessFileHash": "source", "engineCommit": "commit", "worktreeSourceHash": "implementation",
            })
            self.write(directory / "summary.json", {"httpRequests": 10, "executionErrors": []})
            # A reaches B and B reaches A. Own-target-only counting would wrongly report two gaps.
            other = "B" if code == "A" else "A"
            row = {"recipeId": f"{code}:baseline", "targetMajor": code, "kind": "RECIPE", "variant": "baseline",
                   "executionStatus": "EXECUTED", "resultInputHash": f"input-{code}", "versions": {"ruleset": "v1"},
                   "rankedCandidates": [{"majorCode": other}], "firstScreen": [{"majorCode": other}] if other == "B" else [],
                   "targetCandidateReached": False, "targetFirstScreenReached": False, "diagnosis": {"code": "NOT_SELF"},
                   "issuedAnswers": [{"questionId": "Q1"}]}
            repeat = dict(row, kind="REPRODUCIBILITY")
            (directory / "runs.jsonl").write_text(json.dumps(row) + "\n" + json.dumps(repeat) + "\n", encoding="utf-8")

    @staticmethod
    def write(path, value):
        path.write_text(json.dumps(value), encoding="utf-8")

    def merge(self, dirs=None):
        return summarize(self.recipes, self.mapping, self.dirs if dirs is None else dirs)

    def test_any_real_recipe_can_prove_a_candidate_path_without_forcing_its_target(self):
        value = self.merge()
        self.assertEqual(value["majorsWithCandidatePath"], 2)
        self.assertEqual(value["majorsWithFirstScreenPath"], 1)
        self.assertEqual(value["executionGate"], "PASS")
        self.assertEqual(value["displayGate"], "PRODUCT_REVIEW_REQUIRED")
        self.assertFalse(value["releaseReady"])
        self.assertTrue(all(not m["targetedCandidatePaths"] for m in value["majors"]))

    def test_missing_recipe_is_not_reported_as_complete(self):
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            self.merge(self.dirs[:1])

    def test_duplicate_recipe_cannot_inflate_coverage(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.merge(self.dirs + self.dirs[:1])

    def test_mixed_implementation_sources_are_rejected(self):
        file = self.dirs[1] / "manifest.json"
        manifest = json.loads(file.read_text())
        manifest["worktreeSourceHash"] = "different"
        self.write(file, manifest)
        with self.assertRaisesRegex(ValueError, "worktreeSourceHash"):
            self.merge()

    def test_changed_fixture_is_rejected(self):
        self.recipes.write_text(self.recipes.read_text() + " ")
        with self.assertRaisesRegex(ValueError, "Fixture mismatch"):
            self.merge()

    def test_replay_difference_fails_execution_gate_without_hiding_findings(self):
        file = self.dirs[0] / "runs.jsonl"
        rows = [json.loads(s) for s in file.read_text().splitlines()]
        rows[1]["resultInputHash"] = "different"
        file.write_text("\n".join(json.dumps(r) for r in rows) + "\n")
        value = self.merge()
        self.assertEqual(value["executionGate"], "FAILED")
        self.assertIn("A:baseline:MERGE_REPRODUCIBILITY_FAILURE", value["executionErrors"])


if __name__ == "__main__":
    unittest.main()
