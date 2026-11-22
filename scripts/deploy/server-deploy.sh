#!/usr/bin/env bash
# 서버에서 실행하는 배포 스크립트. 배포 전용 SSH 키의 강제 명령(command=)으로만 호출한다.
# 요청 형식: deploy <커밋 SHA 40자> <JAR SHA-256 64자> [allow-migration]   JAR은 표준 입력으로 받는다.
# 설치와 키 등록 방법은 scripts/deploy/README.md에 있다.
set -Eeuo pipefail
umask 077

APP_DIR="${JOBE_DIR:-/home/ubuntu/JOBE}"
STATE_DIR="${JOBE_DEPLOY_STATE:-/home/ubuntu/jobe-deploy}"
BRANCH=main
MAX_JAR_BYTES=209715200
KEEP_BACKUPS=10
HEALTH_TRIES="${JOBE_HEALTH_TRIES:-60}"
HEALTH_DELAY="${JOBE_HEALTH_DELAY:-3}"
JAR=build/libs/jobe-0.0.1-SNAPSHOT.jar

say() { printf '[deploy] %s\n' "$*"; }
die() { say "중단: $*"; exit 1; }

# 저장소의 스크립트가 실행 도중 바뀌어도 영향받지 않도록 함수 전체를 읽은 뒤 실행한다.
main() {
  local request="${SSH_ORIGINAL_COMMAND:-$*}"
  [[ "$request" =~ ^deploy\ ([0-9a-f]{40})\ ([0-9a-f]{64})(\ allow-migration)?$ ]] || die "허용하지 않는 요청입니다."
  local sha="${BASH_REMATCH[1]}" jar_sha="${BASH_REMATCH[2]}" allow_migration="${BASH_REMATCH[3]:-}"

  mkdir -p "$STATE_DIR/backup"
  exec 9>"$STATE_DIR/lock"
  flock -n 9 || die "다른 배포가 진행 중입니다."

  WORK="$(mktemp -d "$STATE_DIR/work.XXXXXX")"
  trap 'rm -rf "$WORK"' EXIT

  head -c "$((MAX_JAR_BYTES + 1))" > "$WORK/new.jar"
  [ "$(stat -c %s "$WORK/new.jar")" -le "$MAX_JAR_BYTES" ] || die "JAR이 너무 큽니다."
  [ "$(sha256sum "$WORK/new.jar" | cut -d' ' -f1)" = "$jar_sha" ] || die "JAR 확인값이 다릅니다."

  cd "$APP_DIR"
  preflight
  local old
  old="$(git rev-parse HEAD)"
  select_commit "$sha" "$old" "$allow_migration"
  backup_databases
  keep_current_version
  say "코드 갱신: ${old:0:7} → ${sha:0:7}"
  trap 'on_failure "'"$old"'"' ERR
  SERVICES_CHANGED=no
  umask 022 # 코드와 JAR은 컨테이너에서 읽을 수 있는 권한으로 둔다.
  git merge -q --ff-only "$sha"
  rm -f build/libs/*.jar
  mkdir -p build/libs
  cp "$WORK/new.jar" "$JAR"
  docker compose build -q ai-service app
  SERVICES_CHANGED=yes
  start_services
  check_health
  trap - ERR
  docker image prune -f --filter 'until=168h' >/dev/null || true
  say "완료: ${sha:0:7}"
}

preflight() {
  # 설정 파일 목록은 서버의 .env(COMPOSE_FILE)를 사용한다. 빠지면 다른 프로필로 뜰 수 있으므로 멈춘다.
  grep -q '^COMPOSE_FILE=' .env 2>/dev/null || die ".env에 COMPOSE_FILE이 없습니다."
  unset COMPOSE_FILE
  docker compose config -q || die "compose 설정이 올바르지 않습니다."
  docker compose config --format json | python3 -c '
import json, sys
app = json.load(sys.stdin)["services"]["app"]["environment"]
profiles = [p.strip() for p in app.get("SPRING_PROFILES_ACTIVE", "").split(",")]
sys.exit(0 if "exploration-dev" in profiles and not {"prod", "production"} & set(profiles) else 1)
' || die "프로필이 exploration-dev가 아닙니다."
  if ! git diff --quiet HEAD -- . ':!build' ':!.gradle'; then die "서버에서 수정된 파일이 있습니다."; fi
}

select_commit() {
  local sha="$1" old="$2" allow_migration="$3" changed
  git fetch -q origin "$BRANCH"
  git merge-base --is-ancestor "$sha" FETCH_HEAD || die "${BRANCH}에 없는 커밋입니다."
  git merge-base --is-ancestor "$old" "$sha" || die "서버 코드에서 이어지는 커밋이 아닙니다."
  changed="$(git diff --name-only "$old" "$sha" -- src/main/resources/db/migration ai-service/migrations)"
  if [ -n "$changed" ] && [ -z "$allow_migration" ]; then
    printf '%s\n' "$changed"
    die "DB 변경이 포함되어 있습니다. 확인한 뒤 수동 실행에서 허용해 주세요."
  fi
}

backup_databases() {
  local stamp
  stamp="$(date +%Y%m%d-%H%M%S)"
  # shellcheck disable=SC2016  # 비밀번호는 컨테이너 안에서만 펼친다.
  docker exec jobe-mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --no-tablespaces jobe' \
    2>/dev/null | gzip > "$STATE_DIR/backup/mysql-$stamp.sql.gz"
  docker exec jobe-ai-postgres pg_dump -U jobe_ai jobe_ai | gzip > "$STATE_DIR/backup/pg-$stamp.sql.gz"
  gzip -dc "$STATE_DIR/backup/mysql-$stamp.sql.gz" | tail -n 5 | grep -q 'Dump completed' || die "MySQL 백업이 완전하지 않습니다."
  gzip -dc "$STATE_DIR/backup/pg-$stamp.sql.gz" | tail -n 5 | grep -q 'dump complete' || die "PostgreSQL 백업이 완전하지 않습니다."
  local kind
  for kind in mysql pg; do
    # 이름에 시각이 있어 이름순이 시간순이다.
    find "$STATE_DIR/backup" -name "$kind-*.sql.gz" | sort -r | tail -n "+$((KEEP_BACKUPS + 1))" | xargs -r rm -f
  done
  say "DB 백업: $stamp"
}

keep_current_version() {
  docker tag "$(docker inspect -f '{{.Image}}' jobe-api)" jobe-app:rollback
  docker tag "$(docker inspect -f '{{.Image}}' jobe-ai-service)" jobe-ai-service:rollback
  if [ -f "$JAR" ]; then cp "$JAR" "$WORK/old.jar"; fi
}

start_services() {
  docker compose up -d --no-deps --no-build --force-recreate --wait --wait-timeout 180 ai-service &&
    docker compose up -d --no-deps --no-build --force-recreate app
}

status() { curl -s -m 5 -o /dev/null -w '%{http_code}' "http://127.0.0.1:8080$1" || true; }

check_health() {
  local i
  for ((i = 0; i < HEALTH_TRIES; i++)); do
    if [ "$(status /actuator/health)" = 200 ]; then
      # 로그인하지 않은 탐색 API는 401이다. 404라면 탐색 진단이 꺼진 설정으로 뜬 것이다.
      [ "$(status /api/exploration/v1/availability)" = 401 ] && return 0
    fi
    sleep "$HEALTH_DELAY"
  done
  say "상태 확인 실패: health=$(status /actuator/health) exploration=$(status /api/exploration/v1/availability)"
  return 1
}

on_failure() {
  local old="$1"
  trap - ERR
  set +e
  say "배포 실패. 이전 버전으로 되돌립니다."
  git reset -q --keep "$old" || say "코드를 되돌리지 못했습니다. 서버에서 확인해 주세요."
  rm -f build/libs/*.jar
  if [ -f "$WORK/old.jar" ]; then cp "$WORK/old.jar" "$JAR"; fi
  docker tag jobe-app:rollback jobe-app:latest
  docker tag jobe-ai-service:rollback jobe-ai-service:latest
  if [ "$SERVICES_CHANGED" = yes ]; then
    if start_services && check_health; then say "이전 버전으로 복구했습니다."
    else say "복구하지 못했습니다. 서버에서 바로 확인해 주세요."; fi
  else
    say "서비스는 바뀌지 않았습니다."
  fi
  exit 1
}

main "$@"
