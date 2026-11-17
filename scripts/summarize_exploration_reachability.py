"""Merge finite D08 HTTP audit evidence. Reads reports only; never modifies datasets/DB/approvals."""
import argparse
import hashlib
import json
from pathlib import Path


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def summarize(recipes_path, mapping_path, report_dirs):
    fixture = read(recipes_path)
    recipes = {r["id"]: r for r in fixture["profiles"]}
    mapping = {m["major_code"]: m for m in read(mapping_path)["majors"]}
    fixture_hash = hashlib.sha256(Path(recipes_path).read_bytes()).hexdigest()
    originals, supplemental, manifests, errors = {}, [], [], []
    requests = 0
    for directory in map(Path, report_dirs):
        manifest, summary = read(directory / "manifest.json"), read(directory / "summary.json")
        if manifest["fixtureFileHash"] != fixture_hash:
            raise ValueError(f"Fixture mismatch: {directory}")
        manifests.append(manifest)
        requests += summary["httpRequests"]
        errors.extend(summary["executionErrors"])
        rows = [json.loads(s) for s in (directory / "runs.jsonl").read_text(encoding="utf-8").splitlines() if s]
        for row in rows:
            if row["kind"] == "RECIPE":
                rid = row["recipeId"]
                if rid not in recipes or rid in originals:
                    raise ValueError(f"Unknown/duplicate original recipe: {rid}")
                if row["targetMajor"] != recipes[rid]["target_major"]:
                    raise ValueError(f"Target mismatch: {rid}")
                originals[rid] = row
            else:
                supplemental.append(row)
    if set(originals) != set(recipes):
        raise ValueError(f"Incomplete fixture: {len(originals)}/{len(recipes)}")
    for key in ("harnessVersion", "harnessFileHash", "engineCommit", "worktreeSourceHash", "fixtureFileHash"):
        if len({m[key] for m in manifests}) != 1:
            raise ValueError(f"Shards used different {key}")
    good = [r for r in originals.values() if r["executionStatus"] == "EXECUTED"]
    versions = {json.dumps(r["versions"], sort_keys=True) for r in good}
    if len(versions) > 1:
        raise ValueError("Shards used different catalog versions")
    repro = [r for r in supplemental if r["kind"] == "REPRODUCIBILITY"]
    for row in repro:
        original = originals[row["recipeId"]]
        if any(row.get(k) != original.get(k) for k in ("resultInputHash", "rankedCandidates", "firstScreen")):
            errors.append(f"{row['recipeId']}:MERGE_REPRODUCIBILITY_FAILURE")
    candidates, screens = {m: [] for m in mapping}, {m: [] for m in mapping}
    coverage = set()
    for row in good:
        for m in row["rankedCandidates"]:
            candidates[m["majorCode"]].append(row["recipeId"])
        for m in row["firstScreen"]:
            screens[m["majorCode"]].append(row["recipeId"])
        coverage.update(a["questionId"] for a in row["issuedAnswers"])
    majors = []
    for code, major in mapping.items():
        own = [r for r in originals.values() if r["targetMajor"] == code]
        own.sort(key=lambda r: r["recipeId"])
        majors.append({
            "majorCode": code, "majorName": major["major_name"], "group": major["exploration_group"],
            "candidateReached": bool(candidates[code]), "firstScreenReached": bool(screens[code]),
            "candidatePathsAnyRecipe": candidates[code], "firstScreenPathsAnyRecipe": screens[code],
            "targetedCandidatePaths": [r["recipeId"] for r in own if r.get("targetCandidateReached")],
            "targetedFirstScreenPaths": [r["recipeId"] for r in own if r.get("targetFirstScreenReached")],
            "targetedObservations": [{"recipeId": r["recipeId"], "executionStatus": r["executionStatus"],
                                      "diagnosis": r.get("diagnosis", {}), "resultStatus": r.get("resultStatus")}
                                     for r in own],
        })
    candidate_gaps = [m["majorCode"] for m in majors if not m["candidateReached"]]
    screen_gaps = [m["majorCode"] for m in majors if not m["firstScreenReached"]]
    failed = [r["recipeId"] for r in originals.values() if r["executionStatus"] != "EXECUTED"]
    by_variant = {}
    for row in good:
        value = by_variant.setdefault(row["variant"], {"executed": 0, "targetCandidateReached": 0, "targetFirstScreenReached": 0})
        value["executed"] += 1
        value["targetCandidateReached"] += int(row["targetCandidateReached"])
        value["targetFirstScreenReached"] += int(row["targetFirstScreenReached"])
    return {
        "reportVersion": "D08_HTTP_AUDIT_SUMMARY_V1_0", "manifests": manifests,
        "fixtureComplete": True, "originalRecipes": len(originals), "successfulRecipes": len(good),
        "supplementalProbes": len(supplemental), "reproducibilityProbes": len(repro),
        "httpRequests": requests, "executionErrors": sorted(set(errors)), "failedRecipes": failed,
        "executionGate": "PASS" if not errors and not failed else "FAILED",
        "candidateGate": "PASS_FOR_FINITE_FIXTURE" if not candidate_gaps and not failed and not errors else "UNRESOLVED",
        "displayGate": "OBSERVED_FOR_ALL_MAJORS" if not screen_gaps and not failed and not errors else "PRODUCT_REVIEW_REQUIRED",
        "majorCount": len(mapping), "majorsWithCandidatePath": len(mapping) - len(candidate_gaps),
        "majorsWithFirstScreenPath": len(mapping) - len(screen_gaps),
        "candidateGaps": candidate_gaps, "firstScreenGaps": screen_gaps, "byVariant": by_variant,
        "issuedQuestionCoverage": sorted(coverage), "majors": majors,
        "scope": "376 finite BROAD issued-HTTP recipes, with bounded synthetic reaction/repeat probes; no browser/student/provider/production validation",
        "moreResultsMeaning": "Set difference between ranked candidates and first screen; not evidence of a public more-results endpoint",
        "releaseReady": False, "contentApproved": False, "weightsOrMappingsAutomaticallyChanged": False,
    }


def markdown_report(result):
    lines = ["# D08 전공별 실제 API 경로 도달성 결과", "",
             f"원본 {result['originalRecipes']}개 중 {result['successfulRecipes']}개 실행 성공, 보충 {result['supplementalProbes']}개, HTTP 스택 요청 {result['httpRequests']:,}회.", "",
             f"- 실행 게이트: {result['executionGate']}",
             f"- 전체 후보 경로: {result['majorsWithCandidatePath']}/{result['majorCount']} 전공",
             f"- 첫 화면 경로: {result['majorsWithFirstScreenPath']}/{result['majorCount']} 전공", "",
             "전용 MySQL·합성 계정·Spring MockMvc의 인증된 API 스택 검증이다. TCP/브라우저·학생·실제 AI 제공자·운영 검증이나 콘텐츠 승인을 의미하지 않는다.", "",
             "후보/첫 화면 경로는 모든 원본 recipe에서 관찰한 경로다. 대상 전공 자신의 recipe와 구분한다. 후보에서 첫 화면을 뺀 집합은 감사용이며 공개 더보기 API의 구현/브라우저 접근성을 증명하지 않는다.", "",
             "## 첫 화면 미도달 검토 대상", ""]
    gaps = [m for m in result["majors"] if not m["firstScreenReached"]]
    if not gaps:
        lines.append("이 유한 시험에서는 모든 전공의 첫 화면 경로가 관찰됐다. 모든 입력에서의 노출이나 추천 품질 보장은 아니다.")
    else:
        lines += ["이 recipe 집합에서 미관찰됐다는 뜻이며 구조적 불가능의 증명은 아니다. 점수·매핑·동점 순서를 임의로 바꾸지 않고 제품/콘텐츠 관점에서 검토한다.", "",
                  "| 전공 코드 | 전공명 | 군 | 대상 recipe의 화면 제한 관찰 |", "|---|---|---|---|"]
        for m in gaps:
            reasons = sorted({o["diagnosis"].get("screenLimitReason", o["diagnosis"].get("code", "EXECUTION_FAILURE")) for o in m["targetedObservations"]})
            lines.append(f"| {m['majorCode']} | {m['majorName']} | {m['group']} | {', '.join(reasons)} |")
    lines += ["", "## 전체 전공", "", "경로 수는 실제 학생 수나 성공률이 아니라 서로 다른 원본 합성 recipe의 개수다.", "",
              "| 전공 코드 | 전공명 | 군 | 후보 경로 수 | 첫 화면 경로 수 | 자체4종 후보/첫 화면 |", "|---|---|---|---:|---:|---|"]
    for m in result["majors"]:
        lines.append(f"| {m['majorCode']} | {m['majorName']} | {m['group']} | {len(m['candidatePathsAnyRecipe'])} | {len(m['firstScreenPathsAnyRecipe'])} | {len(m['targetedCandidatePaths'])}/4 · {len(m['targetedFirstScreenPaths'])}/4 |")
    lines += ["", "## 범위와 다음 판단", "",
              "모든 전공의1위·첫 화면을 강제로 맞추는 시험이 아니다. 실제 발급·허용 선택지·상한·봉인/재조회·동일 입력 재현과 후보 진입을 검증했다. 전체 브라우저/접근성 E2E, 실제 콘텐츠 검토/승인, 실제 모델/운영 성능·삭제/배포/복구는 별도다. 학생 파일럿 미실시·사용자 이관 유보 방침은 유지한다.", "",
              "세부 입력/버전/hash·issued/answer revision·생략/예산·군/점수/동점 증거는 summary.json과 각 shard/runs.jsonl을 따른다. 정적 fixture의 NOT_EXECUTED는 생성 당시 상태로 보존하고 이 실행 증거와 구분한다.", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recipes", type=Path, default=Path("datasets/exploration-validation/reachability-profiles.json"))
    parser.add_argument("--mapping", type=Path, default=Path("datasets/exploration/major-mapping.json"))
    parser.add_argument("--reports", type=Path, nargs="+", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--markdown-output", type=Path)
    args = parser.parse_args()
    result = summarize(args.recipes, args.mapping, args.reports)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.markdown_output:
        args.markdown_output.parent.mkdir(parents=True, exist_ok=True)
        args.markdown_output.write_text(markdown_report(result), encoding="utf-8")
    print(json.dumps({k: result[k] for k in ("originalRecipes", "successfulRecipes", "httpRequests", "executionGate", "candidateGate", "majorsWithCandidatePath", "majorsWithFirstScreenPath")}, ensure_ascii=False))
    return 0 if result["executionGate"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
