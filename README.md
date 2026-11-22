# 진로온 (JinroOn) Backend

## 로컬 개발 환경 세팅

### 실행 전 준비
> .env, application-dev.yml 파일은 노션 환경 변수란에 저장되어 있습니다.

**1. ai-service/.env 파일 생성**

git에 포함되지 않으므로 직접 생성해야 합니다.

```
INTERNAL_SERVICE_TOKEN=dev-internal-token
MOCK_MODE=true
```

> `MOCK_MODE=true` 설정 시 실제 LLM API 호출 없이 테스트용 응답이 반환됩니다.

**2. Spring Boot 빌드**

```bash
./gradlew bootJar -x test
```

### 실행

```bash
docker-compose up -d
```

### 접속 주소

| 서비스 | 주소 |
|---|---|
| Spring Boot API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui/index.html |
| AI 서버 | http://localhost:8001 |

### 로컬 회원가입 메일 확인 (Mailpit)

로컬에는 실제 메일 서버(SMTP)가 없어 회원가입·비밀번호 재설정 인증 메일을 보낼 수 없습니다.
Mailpit을 띄우고 백엔드를 `mailpit` 프로필로 실행하면, 메일이 외부로 나가지 않고 Mailpit으로 들어옵니다.

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d mailpit
```

- 백엔드 실행 프로필에 `mailpit`을 추가합니다. 예: `SPRING_PROFILES_ACTIVE=dev,mailpit`
- 받은 메일(인증번호)은 http://localhost:8025 에서 확인합니다.
- `mailpit` 프로필은 `prod` 프로필과 함께 켜면 시작되지 않습니다.

### 주의사항

볼륨이 이미 존재하면 초기 데이터가 생성되지 않습니다.
처음 실행하거나 데이터를 초기화하려면 아래 순서로 실행하세요.

```bash
docker-compose down -v   # 볼륨까지 삭제
docker-compose up -d
```

### 기존 진단 (사용하지 않음)

새 진단은 전공 탐색 진단(`/api/exploration/v1`)만 사용합니다. 기존 진단의 `diagnosis`·`result`·`plan`·`consultation` 코드와 데이터는 남겨 두고 연결만 끊었습니다.

- `/api/diagnoses`, `/api/results`, `/api/plans`, `/api/consultations`는 `410 LEGACY_DIAGNOSIS_RETIRED`를 반환합니다.
- 진단 이력과 대시보드 요약은 탐색 진단만 다룹니다.
- 기존 진단 문항은 자동으로 등록하지 않습니다.

다시 켜야 할 때는 `LEGACY_DIAGNOSIS_ENABLED=true`로 실행합니다. 이때도 새 회차 시작(`POST /api/diagnoses/sessions`)은 410입니다.

### 자주 쓰는 명령어

```bash
# 실행
docker-compose up -d

# 중지
docker-compose down

# 로그 확인
docker-compose logs -f

# 특정 서비스 로그
docker-compose logs -f app

# 재빌드 후 실행
docker-compose up -d --build

# 볼륨까지 삭제 (DB 초기화)
docker-compose down -v
```

### 테스트 실행

통합 테스트는 개발 DB(`jobe`)가 아닌 전용 테스트 DB(`jobe_test`, 포트 13307)만 사용합니다.

```bash
docker compose -f docker-compose.test.yml up -d --wait
```

```bash
JOBE_TEST_DATABASE_URL='jdbc:mysql://127.0.0.1:13307/jobe_test?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Seoul' \
JOBE_TEST_DATABASE_USERNAME=root JOBE_TEST_DATABASE_PASSWORD=jobe-test-only ./gradlew test
```

- 테스트 DB는 메모리에 저장되므로 컨테이너를 내렸다 올리면(`down` → `up`) 비워집니다.
- 테스트가 오류 없이 멈추면 테스트 DB 용량을 확인하고 컨테이너를 다시 올리세요.

## 브랜치 전략

`feature` → `develop` (기본) → `main` (배포)

## 주의사항

- `application.yml`은 수정하지 않음
- `application-dev.yml`: 로컬 개발용
- `application-prod.yml`: 배포용 (git에 포함되지 않음)
