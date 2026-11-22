# 탐색형 진단 카탈로그 입력

문항·선택지·비교 variant·전공 매핑·규칙·의미 사전 원본이다. **비공개로 관리해야 하는 자료**이며,
저장소를 비공개로 전환한다는 전제로 함께 커밋한다.

## 파일

`manifest.json`의 기존 입력 9개는 `통합과정/업데이트준비완료`의 파일을 바이트 그대로 복사해 이름만 바꾼 것이다
(대응은 manifest의 `source`).

## 흥미 응답과 실행 규칙 버전

`ruleset.json`은 기존 V1.5이고, `ruleset-v1-6.json`은 #144에서 추가한 V1.6이다. 적재기는 두 버전을 버전별 해시로 등록한다.
V1.6에서는 유효한 긍정 흥미 응답(75점 또는 100점)이 하나 이상 있을 때 기존 점수 및 후보 선정 기준을 적용한다.
보통이다(50점)는 유효 응답이지만, 긍정 흥미 응답이 하나도 없으면 새 회차에 개인화 후보를 표시하지 않는다.
후보가 없으면 기존 승인 절차를 거친 공통 탐색 활동을 이용할 수 있다.

기존 DB를 쓰는 DEV 환경에서는 첫 새 회차를 시작하기 전에
`EXPLORATION_CATALOG_LOAD_ON_STARTUP=true`와 `EXPLORATION_CATALOG_DRY_RUN=false`로 카탈로그를 한 번 적재한다.
`EXPLORATION_RULESET_VERSION`을 명시해 둔 환경은 `RULESET_V1_6_IMPLEMENTATION_CANDIDATE`로 변경한다.
이미 시작한 회차와 완료한 결과는 각각 시작할 때 고정된 규칙과 저장된 결과를 계속 사용한다.

## 확인

적재기는 적재 전에 각 파일의 SHA-256을 `manifest.json`과 대조한다. 하나라도 다르거나 없으면 적재를 거부한다.

```bash
# 검증만 (DB에 쓰지 않음)
EXPLORATION_CATALOG_LOAD_ON_STARTUP=true EXPLORATION_CATALOG_DRY_RUN=true ./gradlew bootRun
```

## 원본이 바뀌었을 때

1. 새 파일을 이 폴더에 복사한다.
2. `manifest.json`의 `sha256`을 새 값으로 바꾼다.
3. 내용이 바뀌었으면 데이터 버전도 올려야 한다. 같은 버전으로 다른 내용을 적재하면 적재기가 거부한다.

## 테스트

- 적재기 규칙 테스트: `src/test/resources/exploration-fixture`의 가짜 데이터 (`ExplorationCatalogLoaderTest`)
- 실제 데이터 테스트: 이 폴더의 원본을 명세 수량(187/1,360/32/94)으로 확인 (`ExplorationCatalogRealDatasetTest`)
