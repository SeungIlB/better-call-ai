# Docker Compose 서버 배포

Docker Engine과 Compose 플러그인이 설치된 Linux 서버에서 실행한다. Java와 Node를 서버에 별도로 설치할 필요는 없다. 저장소의 Dockerfile이 Java 21로 앱을 빌드하며 Spring Boot가 프론트와 API를 함께 제공한다.

## 최초 설정

저장소 루트에서 `.env.example`을 `.env`로 복사하고 값을 설정한다. 기존 `.env`가 있다면 덮어쓰지 않는다.

```bash
cp .env.example .env
chmod 600 .env
```

- `DATABASE_MIGRATION_PASSWORD`, `DATABASE_AUTH_PASSWORD`, `DATABASE_APP_PASSWORD`: 서로 다른 비밀번호.
- `JWT_PRIVATE_KEY_BASE64`, `JWT_PUBLIC_KEY_BASE64`, `IDENTITY_ENCRYPTION_KEY_BASE64`, `IDENTITY_LOOKUP_KEY_BASE64`: README의 OpenSSL 명령으로 생성한 실제 키. 재배포 시 기존 키를 유지한다.
- `JWT_ISSUER`: 접속 주소(예: `http://서버IP:8080` 또는 `https://서비스도메인`).
- `OPENAI_API_KEY`, `OPENAI_CHAT_MODEL`, `OPENAI_OCR_MODEL`: 채팅·OCR·분석을 사용할 때 설정. OCR 추론 옵션은 선택한 모델에 맞춘다.
- `LAW_OPEN_DATA_OC`: 법령·판례 외부 조회에 사용할 값.

Compose는 앱의 DB·Redis 주소를 각각 `postgres:5432`, `redis:6379`로 지정한다. `.env`의 localhost 연결 주소는 기존 호스트 `bootRun`용으로 유지할 수 있다. 앱·인증 DB 계정명은 초기화 SQL과 동일한 `legal_ai_app`, `legal_ai_auth`로 고정한다.

## 실행과 확인

```bash
docker compose config --quiet
docker compose up -d --build
docker compose ps
curl --fail --retry 30 --retry-delay 2 --retry-connrefused http://localhost:8080/actuator/health
```

기본 접속 주소는 `http://서버IP:8080`이다. 서버 방화벽에서 앱 포트를 허용한다. `SERVER_PORT`로 공개 포트를 바꿀 수 있으며 컨테이너 내부 포트는 8080으로 유지한다. 프론트는 `/`, API는 `/api/v1`에서 제공한다. HTTPS는 별도 리버스 프록시에서 설정하며, 같은 서버의 프록시만 접근하게 하려면 `APP_BIND_ADDRESS=127.0.0.1`로 설정한다.

PostgreSQL·Redis의 헬스 체크가 통과한 뒤 앱이 시작하고 Flyway가 DB 스키마를 적용한다. 파일 업로드는 바이러스 검사 없이 형식·크기·페이지 제한을 확인한다. 앱·DB·빌드에 필요한 메모리를 확보한다.

외부 API 키와 법률 검색 데이터는 Compose가 생성하지 않는다. RAG용 법률 데이터 수집·임베딩은 README의 데이터 수집 절차를 별도로 수행한다. 배포 확인은 헬스 응답, 첫 화면, 회원가입·로그인·사건 생성 순서로 진행하고 외부 연동 설정 후 업로드·OCR·분석을 확인한다.

## 저장과 재배포

PostgreSQL, Redis 데이터와 OCR 확인 대기 중인 임시 원본은 Docker 이름 있는 볼륨에 보관한다. 임시 원본은 컨테이너 재생성 시에도 유지되지만 기존 확정·만료 삭제 정책은 그대로 적용된다. DB·Redis 포트는 호스트의 loopback에만 공개한다.

```bash
# 서버의 코드를 갱신한 뒤 앱 재빌드·재생성
docker compose up -d --build
# 앱 로그 확인
docker compose logs --tail=100 app
# 볼륨을 보존하고 중지
docker compose down
```

`docker compose down -v`는 DB를 포함한 볼륨을 삭제하므로 일반 중지·재배포에 사용하지 않는다. DB 비밀번호와 초기 계정 생성은 빈 PostgreSQL 볼륨의 첫 실행 때 적용된다. 기존 볼륨의 비밀번호는 `.env`만 바꿔도 변경되지 않는다. DB와 암호화 키는 별도로 백업한다.

호스트에서 Java 앱을 실행하는 개발 방식은 `docker compose up -d postgres redis` 후 Windows에서 `.\scripts\run-local.ps1`을 사용한다. Compose 앱과 호스트 앱을 같은 포트에 동시에 실행하지 않는다.
