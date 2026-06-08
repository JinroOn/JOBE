# 진로온 (JinroOn) Backend

## 로컬 개발 환경 세팅

### 실행 전 준비

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

### 초기 데이터 (자동 생성)

docker-compose 최초 실행 시 아래 데이터가 자동으로 생성됩니다.

**테스트 계정**

| 역할 | 이메일 | 비밀번호 |
|---|---|---|
| 관리자 | admin@jinroon.com | test1234! |
| 유저1 | user1@jinroon.com | test1234! |
| 유저2 | user2@jinroon.com | test1234! |

- 전공 데이터 10개
- 공지사항 2개

### 주의사항

볼륨이 이미 존재하면 초기 데이터가 생성되지 않습니다.
처음 실행하거나 데이터를 초기화하려면 아래 순서로 실행하세요.

```bash
docker-compose down -v   # 볼륨까지 삭제
docker-compose up -d
```

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

## 브랜치 전략

`feature` → `develop` (기본) → `main` (배포)

## 주의사항

- `application.yml`은 수정하지 않음
- `application-dev.yml`: 로컬 개발용
- `application-prod.yml`: 배포용 (git에 포함되지 않음)
