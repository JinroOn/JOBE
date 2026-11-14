# 탐색형 진단 카탈로그 입력

문항·선택지·비교 variant·전공 매핑·규칙·의미 사전 원본이다. **비공개로 관리해야 하는 자료**이며,
저장소를 비공개로 전환한다는 전제로 함께 커밋한다.

## 파일

`manifest.json`의 `files` 9개. 원본은 `통합과정/업데이트준비완료`의 파일을 바이트 그대로 복사해 이름만 바꾼 것이다
(대응은 manifest의 `source`).

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
