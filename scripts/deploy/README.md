# 데모 서버 자동 배포

`main`에 반영되면 `.github/workflows/deploy.yml`이 테스트 후 JAR을 만들어 서버로 보냅니다. 서버의 `server-deploy.sh`가 아래 순서로 처리합니다.

1. 요청 형식, JAR 확인값, 서버 설정(프로필 `exploration-dev`)을 확인합니다.
2. `main`에 있고 서버 코드에서 이어지는 커밋인지 확인합니다.
3. DB 변경이 있으면 멈춥니다. 확인한 뒤 Actions에서 `allow_migration`을 켜고 수동 실행합니다.
4. MySQL과 PostgreSQL을 백업합니다(최근 10개 보관).
5. 이미지를 만들고 AI 서비스와 백엔드를 교체합니다. DB 컨테이너는 바꾸지 않습니다.
6. 상태 확인에 실패하면 이전 이미지와 코드로 되돌립니다. **DB는 되돌리지 않습니다.**

활동·AI 근거의 적재와 승인, `.env` 변경은 자동으로 하지 않습니다.

## 준비 (한 번)

1. 배포 전용 키를 새로 만듭니다. 기존 접속 키를 쓰지 않습니다.
2. 이 스크립트를 저장소 밖에 설치합니다. 배포로 바뀌지 않게 하기 위해서이며, 스크립트를 고치면 다시 설치합니다.

   ```bash
   install -D -m 700 scripts/deploy/server-deploy.sh /home/ubuntu/jobe-deploy/server-deploy.sh
   ```

3. 서버의 `~/.ssh/authorized_keys`에 공개 키를 강제 명령과 함께 한 줄로 추가합니다. 이 키로는 배포 스크립트만 실행됩니다.

   ```text
   restrict,command="/home/ubuntu/jobe-deploy/server-deploy.sh" ssh-ed25519 <공개 키> jobe-deploy
   ```

4. GitHub 저장소 시크릿을 등록합니다.

   | 이름 | 내용 |
   | --- | --- |
   | `DEPLOY_HOST` | 서버 주소 |
   | `DEPLOY_SSH_KEY` | 배포 전용 비밀 키 |
   | `DEPLOY_KNOWN_HOSTS` | 서버 호스트 키(`known_hosts` 형식). 서버에서 직접 확인한 값을 사용합니다. |

5. 서버의 `/home/ubuntu/JOBE/.env`에 `COMPOSE_FILE`이 있어야 합니다.
