# 94개 전공 DEV 승인 재현

[2026-11-20 승인 기록](all94-20261120.json)은 JY가 승인한 PR #133의 원본, 두 카탈로그 버전과 콘텐츠 해시를 고정합니다. 대상은 근거 94건과 활동 카탈로그·활동의 구현/내용 판정 566건입니다. 승인 참조는 [전공 검토 및 검증](https://app.notion.com/p/41e7e16f06d280058e1ae5a9d59fba19)에 있습니다.

## 원본 준비

격리한 로컬 DEV MySQL, PostgreSQL과 관리자 계정을 준비하고 저장소의 DB 마이그레이션을 적용합니다. 아래 명령은 Bash 기준이며 JOBE 루트에서 실행합니다. Python 의존성은 `ai-service/requirements.txt`를 사용합니다.

```bash
tooling=$(git rev-parse HEAD)
git worktree add --detach ../JOBE-dev-approved 0407f58b4df8cf3814bd9f518d24b512ad45af00
cd ../JOBE-dev-approved
git restore --source "$tooling" -- ai-service/scripts/prepare_dev_approval_replay.py datasets/dev-approvals/all94-20261120.json
python ai-service/scripts/prepare_dev_approval_replay.py --output ../jobe-dev-approval-plan
```

현재 체크아웃에서 승인 당시 Git blob과 RAG 콘텐츠 해시가 모두 일치해야 출력이 생성됩니다. 이후 수정된 근거가 포함된 체크아웃은 검증에서 차단됩니다. 신규 내용은 별도 버전과 승인으로 진행합니다.

## PostgreSQL 근거 등록

`$AI_ENV`는 격리한 DEV PostgreSQL의 연결 설정 파일, `$PG_CONTAINER`는 그 컨테이너 이름입니다. `stage_rag_review.py`는 보고서를 저장소 밖 새 경로에 생성합니다. `--apply`를 빼면 사전 점검만 수행합니다.

```bash
drafts=()
for draft in datasets/exploration-rag-review/*.review-draft.json \
  datasets/exploration-rag-review/computer-science.pilot-review.json; do
  drafts+=(--draft "$draft")
done
python ai-service/scripts/stage_rag_review.py "${drafts[@]}" \
  --batch-version EXPLORATION_RAG_DEV_ALL94_20261120_V1 \
  --env-file "$AI_ENV" --source-container "$PG_CONTAINER" \
  --report ../jobe-rag-staged.json --apply
python ai-service/scripts/prepare_dev_approval_replay.py \
  --staged-rag ../jobe-rag-staged.json --output ../jobe-dev-approval-receipts
for receipt in ../jobe-dev-approval-receipts/MAJOR_ROW_*.json; do
  code=$(basename "$receipt" .json)
  python ai-service/scripts/review_rag_evidence.py record --receipt "$receipt" \
    --env-file "$AI_ENV" --source-container "$PG_CONTAINER" \
    --report "../jobe-rag-apply-$code.json" --apply || exit 1
done
```

위 절차는 대상 DB의 새 문서·청크 ID에 맞춰 승인 영수증을 생성합니다. 재실행할 때는 새 보고서 경로를 사용합니다. 동일 승인 기록은 `UNCHANGED`, 이후 다른 판정이 기록된 문서는 충돌로 처리됩니다. 검증 대상은 근거 94건, 청크 188개, `DEV` 승인 94건입니다.

## MySQL 활동 카탈로그 등록

1. `activities.json`의 `major_mapping_version`과 같은 전공 카탈로그를 먼저 적재합니다. 출력된 `activities.json`을 `EXPLORATION_ACTIVITY_DATASET_PATH`로 지정하고, 검증용 백엔드에서 `EXPLORATION_ACTIVITY_CATALOG_VERSION=ACTIVITY_CATALOG_DEV_ALL94_20261120_V1`, `EXPLORATION_ACTIVITY_LOAD_ON_STARTUP=true`, `EXPLORATION_ACTIVITY_DRY_RUN=false`로 적재합니다.
2. 관리자 API `GET /api/admin/exploration/catalogs?targetType=ACTIVITY_CATALOG`에서 버전과 카탈로그 해시를 찾습니다. `GET /api/admin/exploration/catalogs/ACTIVITY_CATALOG/{catalogId}/activities`에서 활동 ID를 조회합니다. 승인 전에 `activity-target-hashes.json`과 카탈로그 해시, 활동 코드 집합 및 각 활동 해시가 일치하는지 확인합니다.
3. 카탈로그는 `ACTIVITY_CATALOG`, 항목은 `ACTIVITY`를 사용합니다. `GET /api/admin/exploration/reviews?targetType=...&targetId=...`에서 각 판정 종류의 최신 기록을 확인합니다. 같은 버전·해시의 DEV 승인은 건너뛰고, 반려 등 다른 판정이 있으면 검토를 요청합니다. 판정이 없으면 인가된 로컬 관리자가 `POST /api/admin/exploration/reviews`에 아래 형식으로 `IMPLEMENTATION`과 `DOMAIN` 승인을 각각 등록합니다. 타깃 ID와 기록 작성자는 대상 환경에서 부여됩니다. 증거 참조에는 원 승인자 JY와 원본 기록 위치를 남깁니다.

```json
{
  "targetType": "ACTIVITY_CATALOG",
  "targetId": 1,
  "reviewKind": "IMPLEMENTATION",
  "decision": "APPROVED",
  "evidenceReference": "JY DEV 승인 2026-11-20 · https://app.notion.com/p/41e7e16f06d280058e1ae5a9d59fba19"
}
```

검증 대상은 카탈로그 1개와 활동 282개, 각각 구현과 내용 승인 2건으로 총 566건입니다. DEV 추천을 확인할 때는 DEV 실행 범위와 위 카탈로그 버전을 지정합니다. 파일과 영수증은 DEV 범위의 판정에 사용합니다. 공개 승인 및 운영 활성화는 대상 환경에서 따로 진행합니다.
