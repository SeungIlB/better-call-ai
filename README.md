# Better Call AI Backend

개인 사용자가 자신의 생활 분쟁을 정리하고, OCR 수정본·법률 근거·AI 분석·대응 문서를 한 사건 단위로 관리하는 API 서버의 개발 기반입니다.

## 현재 구현 범위

- PostgreSQL 16 + pgvector 전체 Flyway migration V001~V016
- migration / auth / application DB role 분리
- JWT 회원가입·로그인·재발급·로그아웃·내 정보 API
- JWT RS256 서명·만료·발급자·audience 검증과 공통 401/403 응답
- Refresh Token 해시 저장·1회성 회전·재사용 탐지
- PostgreSQL 기반 이메일별 로그인 실패 제한 (연속 5회 실패 시 15분 잠금)
- 이메일 AES-256-GCM 암호화와 HMAC 조회값 분리
- 요청 트랜잭션마다 `app.user_id`를 주입하는 PostgreSQL RLS 경계
- 사건 생성·조회·진술 수정 vertical slice
- 내 사건 요약 목록 페이지 조회 및 7일 유예 논리 삭제
- 임시 원본 업로드·형식/크기/페이지 검증·파일 소유권 및 메타데이터 조회
- 로컬 원본 만료·사건 삭제 후 정리와 삭제 실패 상태 기록
- 법제처 국가법령정보 공동활용 API의 법령·판례 검색 및 본문 정규화
- 진술 수정 시 optimistic lock, 기존 분석 stale 처리, outbox 발행
- 공통 오류 응답과 trace ID
- 공통 성공 응답과 안전한 trace ID 전달
- OpenAPI 3.1 계약
- Testcontainers 기반 실제 PostgreSQL 통합 테스트

OCR 공급자, OpenAI Vector Store/RAG, 객체 저장소, outbox worker와 DB 영구 삭제 배치는 구현 전 단계입니다.

## 기술 기준

- Java 21 (record·text block 사용)
- Lombok (`@RequiredArgsConstructor`, `@Builder`, `@Slf4j`)
- Spring Boot 4.1
- Gradle Wrapper
- PostgreSQL 16 / pgvector
- Flyway
- Spring JDBC
- Spring Security OAuth2 Resource Server
- Testcontainers

## 로컬 실행

`.env.example`을 복사한 뒤 세 데이터베이스 비밀번호를 각기 다른 로컬 값으로 변경합니다. 예제 값을 운영 환경에 사용하지 않습니다. 비밀번호가 비어 있으면 애플리케이션과 PostgreSQL 컨테이너는 기동에 실패합니다.

JWT 서명용 RSA 키와 개인정보 보호용 키도 서로 다른 값으로 생성합니다. 개인키는 PKCS#8 DER, 공개키는 X.509 DER를 Base64 한 줄로 설정합니다.

```powershell
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
openssl pkcs8 -topk8 -nocrypt -in jwt-private.pem -outform DER | openssl base64 -A
openssl pkey -pubin -in jwt-public.pem -outform DER | openssl base64 -A
openssl rand -base64 32
openssl rand -base64 32
```

출력값을 순서대로 `JWT_PRIVATE_KEY_BASE64`, `JWT_PUBLIC_KEY_BASE64`, `IDENTITY_ENCRYPTION_KEY_BASE64`, `IDENTITY_LOOKUP_KEY_BASE64`에 넣습니다. PEM 파일과 실제 키는 저장소에 커밋하지 않습니다.

```powershell
Copy-Item .env.example .env
docker compose up -d postgres
.\gradlew.bat bootRun
```

헬스 체크:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

애플리케이션 API는 UUID 형식의 JWT `sub`가 필요합니다. 인증 API가 발급한 Access Token을 Bearer Token으로 전달합니다.
JWT는 RS256 서명, `exp`/`nbf`, `iss`, `aud`를 모두 검증합니다. `aud`에는 `JWT_AUDIENCE` 값이 포함되어야 하며 사용자 식별에는 요청 본문이나 쿼리의 user ID가 아닌 JWT `sub`만 사용합니다.

법령·판례 검색은 [국가법령정보 공동활용](https://open.law.go.kr/) 승인 후 발급 기준에 맞는 `LAW_OPEN_DATA_OC`를 `.env`에 설정합니다. 값이 없어도 서버와 다른 기능은 기동되며, 법률 데이터 API 호출만 `503 INTEGRATION_NOT_CONFIGURED`를 반환합니다.

## 검증

```powershell
.\gradlew.bat clean test
docker compose config
```

통합 테스트는 실제 `pgvector/pgvector:pg16` 컨테이너를 띄우고 다음을 확인합니다.

1. V001~V014 migration 전체 성공
2. 사용자 A의 사건 생성
3. 진술 변경 시 version 증가
4. 같은 트랜잭션의 outbox 2건
5. 사용자 B의 사건 조회 차단
6. 회원가입·로그인과 암호화된 이메일 조회
7. Refresh Token 해시 저장·회전·재사용 시 후속 토큰 폐기
8. 반복 로그아웃·타인 토큰 폐기 차단·만료/정지 계정 재발급 거부
9. 동시 재발급과 서로 다른 세션의 재사용 시 교착 없이 모든 Refresh Token 폐기
10. 미가입 이메일을 포함한 실패 제한, 동시 실패 횟수 집계, 성공/잠금 만료 후 초기화
11. 실제 발급 JWT와 MockMvc 보안 필터 체인으로 사건 생성·조회·수정 소유권 검증
12. 본문·쿼리·헤더의 사용자 ID 위조 차단, 타인/없는 사건의 동일 404, 거부 후 DB 전체 행·outbox 불변
13. 소유자의 정상 수정과 오래된 버전 409, 익명 요청 401, 단일 연결 풀 재사용 시 사용자 범위 초기화
14. 본인 활성 사건 목록의 페이지 경계·빈 목록·정렬·잘못된 페이지 입력 검증
15. 사건 삭제 후 연결 파일 접근 차단, 반복 삭제 404, outbox 실패 시 삭제 전체 롤백

## 중요한 보안 경계

- `legal_ai_migrator`: DDL owner이자 migration 전용 role
- `legal_ai_auth`: 인증 테이블 전용 role; casework 권한 없음
- `legal_ai_app`: API role; `NOBYPASSRLS`
- API repository 호출은 반드시 `UserScopedTransaction` 안에서 수행
- 사건이 없거나 타인 소유이면 모두 404로 처리
- 논리 삭제는 소유권을 재검증하는 한정된 SECURITY DEFINER 함수로 수행하며 SELECT RLS 범위를 넓히지 않음
- 원본 파일 장기 보관 금지; 확정 OCR 수정본만 영속 보관
- 비밀값·원문·복호화 개인정보를 로그에 기록하지 않음

## 선행 안전 정책

- JWT는 RS256 서명, 만료·활성시간, 발급자, audience를 검증
- 로컬 비밀번호는 cost 12 BCrypt 형식만 허용
- refresh token은 원문 대신 소문자 SHA-256 해시만 저장
- Access Token은 15분, Refresh Token은 14일이며 재발급마다 Refresh Token을 교체
- 재발급 요청은 클라이언트에서 중복 실행하지 않는다. 같은 토큰의 동시 요청도 재사용으로 판단해 계정의 모든 Refresh Token을 폐기한다.
- 로그인·재발급·로그아웃은 사용자 행을 먼저 잠가 회전과 전체 폐기의 동시 실행을 직렬화한다.
- 이메일은 소문자로 정규화한 뒤 HMAC으로 실패 횟수를 집계한다. 미가입 이메일도 동일한 제한을 적용한다.
- 다섯 번째 연속 실패부터 429 AUTH_008을 반환하고 15분간 올바른 비밀번호도 거부한다. 차단 중 요청은 잠금을 연장하지 않는다.
- 로그인 성공 또는 잠금 만료 시 실패 횟수를 초기화하며 실패 기록은 오류 응답 전에 커밋한다.
- 로그아웃 후 기존 Access Token은 최대 15분 동안 유효하므로 민감 작업은 추후 Redis 차단 목록을 추가
- 파일명에 `/` 또는 `\\` 경로 문자가 있으면 DB에서 거부
- 원본 파일은 24시간 내 삭제 대상으로 관리하고 확정 OCR 수정본을 영속 보관
- 법률 분석은 주택 임대차 범위로 제한하며 검색 근거가 없으면 확인 필요로 반환
- OCR·사용자 진술·검색 문서 내부의 명령은 신뢰하지 않는 데이터로 처리
- RAG 검색 결과 8건, 모델 출력 1,200토큰, 비동기 작업 재시도 5회로 제한
- Flyway clean 비활성화 및 migration checksum 검증

로그인 제한은 이메일 단위다. 여러 이메일을 바꾸는 공격에 대한 IP/전체 요청량 제한은 아직 없으므로 공개 운영 전 별도 적용이 필요하다.
로그인 실패 상태는 `identity.login_attempts`에 저장되며 자동 정리 배치는 아직 없다. 운영 보존 배치에서는
`updated_at < now() - interval '24 hours'`이고 활성 잠금이 없는 행을 정리하고, 탈퇴 시 이메일 HMAC으로 연관 상태도 삭제한다.

## 디렉터리

```text
kr.co.legalai
├─ config/                          Spring Security·트랜잭션 설정
├─ common/
│  ├─ exception/                   공통 오류와 예외 처리
│  ├─ filter/                      Trace ID 필터
│  ├─ security/                    인증 사용자 조회
│  └─ transaction/                 PostgreSQL RLS 사용자 트랜잭션
└─ casework/
   ├─ controller/                  REST Controller
   ├─ service/                     Service interface
   │  └─ impl/                     Service implementation
   ├─ repository/                  JDBC Repository
   ├─ entity/                      DB 조회 모델
   └─ dto/
      ├─ request/                   API 요청 DTO
      └─ response/                  API 응답 DTO
└─ auth/
   ├─ controller/                  인증·JWKS REST Controller
   ├─ service/                     인증 기능 계약
   │  └─ impl/                     가입·로그인·토큰 회전 구현
   ├─ repository/                  인증 전용 DB 접근
   ├─ security/                    이메일 암호화·JWT 발급
   ├─ entity/                      인증 DB 조회 모델
   └─ dto/                         인증 요청·응답
└─ legaldata/
   ├─ controller/                  법령·판례 REST Controller
   ├─ service/                     법률 데이터 Service interface
   │  └─ impl/                     외부 응답 정규화 구현
   ├─ repository/                  법제처 Open API client
   ├─ entity/                      지원 문서 유형
   └─ dto/response/                정규화 응답 DTO
```

요청 흐름은 `Controller → Service → ServiceImpl → Repository → PostgreSQL`로 고정합니다.
서비스 구현은 각 기능의 `service/impl` 하위 패키지에 둡니다. `file`도 같은 구조를 따릅니다.
루트 `api/`는 Java 계층이 아니라 OpenAPI 계약 문서 디렉터리입니다.

## 현재 REST API

- `POST /api/v1/auth/register` — 약관·개인정보 동의를 포함한 회원가입
- `POST /api/v1/auth/login` — 로그인 및 Token Pair 발급
- `POST /api/v1/auth/refresh` — Refresh Token 1회성 회전
- `POST /api/v1/auth/logout` — 현재 Refresh Token 폐기
- `GET /api/v1/auth/me` — 인증 사용자 정보 조회
- `GET /.well-known/jwks.json` — JWT 검증 공개키
- `POST /api/v1/cases` — 사건 생성
- `GET /api/v1/cases?page=1&pageSize=20` — 내 활성 사건 요약 목록
- `GET /api/v1/cases/{caseId}` — 사건 단건 조회
- `PATCH /api/v1/cases/{caseId}` — 사건 진술 수정
- `DELETE /api/v1/cases/{caseId}` — 사건 논리 삭제 및 영구 삭제 예정 시각 기록
- `POST /api/v1/cases/{caseId}/files` — multipart `file` 임시 업로드
- `GET /api/v1/cases/{caseId}/files/{fileId}` — 자기 파일 메타데이터 조회
- `GET /api/v1/legal-data/law?query=민법` — 법령 검색
- `GET /api/v1/legal-data/precedent?query=임대차%20수선의무` — 판례 검색
- `GET /api/v1/legal-data/{type}/{externalId}` — 법령·판례 본문 정규화 조회

## 다음 구현 순서

1. PDF·이미지 파싱을 별도 제한 프로세스로 격리
2. OCR outbox worker·수정본 확정·확정 시 즉시 원본 purge
3. 확인 질문·사실 충돌 해결
4. 법률 문서 적재·구조 기반 청킹·OpenAI Vector Store 연동
5. RAG 검색과 분석 실행
6. 대응 계획·문서 생성
7. 보존기간·탈퇴 purge job

## 사건 목록과 삭제 계약

목록은 최근 수정순(`updated_at DESC, id DESC`)으로 정렬하고, 페이지당 한 건을 더 조회해
`hasNext`를 계산한다. `page`는 1~10,000, `pageSize`는 1~100(기본 20)이다. 허용 범위를 벗어난 입력은 400이며, 유효한 페이지에 사건이 없으면 빈 배열을 반환한다.
응답은 `ApiResponse<PageResponse<CaseSummaryResponse>>` 형식이며 진술·목표 본문은 단건 조회에서만 제공한다.
Offset 방식이므로 페이지 사이 사건 변경이 발생하면 항목 위치가 이동할 수 있다.

삭제는 `deleted_at`, `hard_delete_after`, 증가한 버전을 기록하고 현재 분석 참조를 해제한 뒤
`CASE_DELETED` outbox 이벤트를 같은 트랜잭션에 저장한다. 타인·없는 사건·기삭제 사건은 모두 404다.
유예 기간은 `ops.runtime_settings['case.delete_grace_days']`(기본 7일)를 사용한다.
사건과 연결 데이터는 RLS에 의해 즉시 숨겨지지만 실제 데이터는 유예 기간 동안 남는다.
현재 DB 영구 삭제 배치와 객체 저장소 파기 worker는 미구현이며, 204는 논리 삭제 완료만 의미한다.
로컬 임시 원본은 사건 삭제 후 다음 원본 정리 주기에 삭제한다.
추후 worker는 작업 실행 전에 사건 삭제 여부를 재검증해 기존 OCR·AI 이벤트 실행을 중단하고,
임시 원본의 기존 24시간 보관 한도를 지켜 파기한 뒤 영구 삭제 예정 시각에 연관 DB 데이터를 정리해야 한다.

## 임시 원본 업로드 계약

- 기본 제한: PDF·PNG·JPEG·WebP, 파일당 20MiB, PDF 1~30쪽, 사건당 10개·100MiB.
- 파일명 경로·제어 문자, MIME/확장자/헤더 불일치, 파싱 불가·암호화 PDF를 거부한다. 이미지도 디코딩하고 10,000px/변 및 2천만 화소를 제한한다.
- 원본 파일명은 메타데이터에만 저장하며 실제 경로는 서버가 생성한 UUID이다. SHA-256과 실제 크기·페이지 수를 기록한다.
- 사건 잠금과 RLS로 소유권 및 동시 업로드 한도를 검증한다. 메타데이터와 FILE_UPLOADED 이벤트는 같은 트랜잭션이며 롤백 시 원본 삭제를 시도한다.
- 신규 업로드는 ClamAV 검사 후 PDF·이미지 파싱을 진행한다. 성공은 UPLOADED/clean이며 검사 공급자와 검사 시각을 DB에 기록한다. 기존 pending 파일은 소급해서 clean으로 바꾸지 않는다.
- 로컬 경로는 `UPLOAD_TEMP_DIR`(기본 `./storage/temp-originals`). 전용 비공개 디렉터리와 제한된 OS 접근 권한이 필요하다. 단일 인스턴스 MVP용이며 다중 서버·공유 저장소 운영은 지원하지 않는다.
- 1분마다 만료(기본 24시간) 또는 삭제된 사건의 원본을 최대 100개 정리한다. 삭제 확인 후 경로를 비우고 purged로 기록한다. 만료 시각은 정리 대상 시각이며 실행 주기·장애 때문에 실제 삭제가 늦어질 수 있다.
- 추적 중인 원본 삭제 실패는 5분 간격 최대 5회 후 failed로 남긴다. failed 상태는 운영자가 확인해야 하며 무한 자동 재시도하지 않는다.
- DB에 연결되지 않은 UUID 원본은 24시간 이후 정리한다. 고아 원본은 DB 재시도 이력이 없으며 삭제 실패 시 후속 스캔에서 다시 시도한다.
- 원본 다운로드, OCR, OCR 확정 시 즉시 삭제는 후속 작업이다. 인터넷 공개 전 별도 프로세스 파싱 격리와 순간 요청 속도 제한을 보완해야 한다.

## 업로드 플랜과 중복 요청

- FREE는 UTC 하루 10회(한국 시각 오전 9시 초기화), 유효한 PAID는 일일 횟수·총용량 제한이 없다. 기존 파일당 20MiB·PDF 30쪽·사건당 10개/100MiB와 동시 검사 2건 제한은 유지한다.
- 상품 정책은 `ops.upload_plan_limits`, 사용자 권한은 `identity.user_plans`에서 관리한다. 권한 행이 없거나 유료 기간이 만료되면 무료이며 API 요청의 플랜 값은 사용하지 않는다.
- 앱 DB 역할은 플랜을 읽기만 할 수 있다. 결제·승급 API는 미구현이며 권한 부여는 운영 DB 역할에서만 가능하다. 기존 가입자는 기본 무료다.
- `Idempotency-Key` UUID 헤더가 필수다. 같은 사용자·키·사건·파일명·정규화 MIME·실제 파일 내용이면 재저장·재검사 없이 같은 파일의 현재 메타데이터를 201로 반환한다.
- 다른 내용을 같은 키에 보내면 409 FILE_010, 처리 중이면 409 FILE_011, 무료 횟수 초과는 429 FILE_009다. 키 누락·형식 오류는 400이다. 키는 사용자별로 구분된다.
- 동일 키 실패 재요청은 저장된 오류를 반환하며 검사와 차감을 반복하지 않는다. 명시적으로 새 시도를 하려면 새 키를 사용한다. 성공한 파일의 사건이 삭제되면 재요청도 404다.
- 검사 전에 별도 트랜잭션으로 사용량을 예약한다. 예약 이후 검사 장애·내용 오류·경합 실패도 횟수에 포함되며 환급하지 않는다. 기본 입력 오류·한도 초과로 예약하지 못한 요청은 차감하지 않는다.
- 사용량 예약은 DB 행 잠금으로 동시성 검증한다. 유료의 바이트·횟수도 통계로만 기록하며 일일 상한을 적용하지 않는다. 사건 삭제로 일일 사용량이 초기화되지 않는다.
- 멱등성 기록은 사건 영구 삭제 시 함께 삭제된다. 30일보다 오래된 일일 사용량은 해당 사용자의 다음 신규 예약 시 정리한다. 활동이 없는 사용자는 계정 삭제 전까지 기록이 남을 수 있다.
- 프로세스 강제 종료 등으로 PROCESSING이 남은 키는 자동 재실행하지 않는다. 운영 확인이 필요하며 클라이언트가 무한 재시도해서는 안 된다. DB/저장소 상태를 확인하지 않고 해당 기록을 삭제하지 않는다.
- multipart 수신과 내용 해시 계산은 재요청에도 필요하다. 이번 작업은 검사·저장의 중복 방지이며 네트워크 요청 속도 제한은 별도 작업이다. OCR·채팅의 상품 사용량과 토큰 제한은 아직 연결하지 않았다.

## ClamAV 검사 실행

```powershell
docker compose up -d clamav
docker compose ps clamav
```

- healthy 상태를 확인한 뒤 업로드한다. 최초 이미지·서명 다운로드와 서명 로딩에 시간이 필요하다. 기본 연결은 `127.0.0.1:3310`이며 별도 API 키가 없다.
- 환경 변수는 `CLAMAV_HOST`, `CLAMAV_PORT`, `CLAMAV_TIMEOUT_MS`(기본 10000, 최대 30000ms). IntelliJ 실행 시 환경 변수를 전달한다. 실제 `.env`는 변경하지 않았다.
- 검사 우회 플래그는 없다. 서버 시작·인증 등은 ClamAV 없이 가능하지만 파일 업로드는 검사 서버가 필요하다. 자동 테스트만 가짜 검사 서버/테스트 대역을 사용한다.
- INSTREAM으로 파일명·로컬 경로 대신 바이트를 전송한다. 연결 최대 2초, 통신 전체 기본 10초, 인스턴스당 동시 검사 2건이며 초과 요청은 503이다. 자동 재시도는 하지 않는다.
- 명확한 정상 응답만 통과한다. 탐지·검사 한도 초과 경고는 422 FILE_007, 장애·잘린 응답·시간 초과는 503 FILE_008이다. 거부된 업로드는 파일 행·outbox를 저장하지 않고 임시 원본 삭제를 시도한다. 삭제 실패는 기존 고아 원본 정리 대상이다.
- Compose는 호스트 loopback에만 포트를 열고, 메모리 4GiB·CPU 2·임시 메모리 파일시스템 256MiB를 제한한다. 검사 최대 8초, 파일 20MiB, 펼친 내용 100MiB, 중첩 10단계·1000개를 제한하고 한도 초과·암호화 내용 경고를 활성화한다.
- 서명 데이터만 Docker 볼륨에 유지하고 FreshClam이 갱신한다. 원본 저장소는 컨테이너에 마운트하지 않는다. ClamAV 자체 통신에는 인증·암호화가 없으므로 외부 네트워크에 노출하지 않는다.
- 검사 중에는 현재 MVP의 사건 잠금·DB 트랜잭션을 유지한다. 처리량이 커지면 제한된 비동기 작업자로 분리해야 한다. 검사 성공이 모든 위협 제거를 보장하지 않으며 Java 파서의 별도 프로세스 격리는 아직 미구현이다.

공식 기준: [ClamD 통신 규약](https://docs.clamav.net/manual/Usage/ClamdProtocol.html), [Docker 실행 가이드](https://docs.clamav.net/manual/Installing/Docker.html).
