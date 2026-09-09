# Better Call AI Backend

개인 사용자가 자신의 생활 분쟁을 정리하고, OCR 수정본·법률 근거·AI 분석·대응 문서를 한 사건 단위로 관리하는 API 서버의 개발 기반입니다.

## 현재 구현 범위

- PostgreSQL 16 + pgvector 전체 Flyway migration V001~V011
- migration / auth / application DB role 분리
- JWT Resource Server 보안 기본값
- JWT RS256 서명·만료·발급자·audience 검증과 공통 401/403 응답
- 요청 트랜잭션마다 `app.user_id`를 주입하는 PostgreSQL RLS 경계
- 사건 생성·조회·진술 수정 vertical slice
- 법제처 국가법령정보 공동활용 API의 법령·판례 검색 및 본문 정규화
- 진술 수정 시 optimistic lock, 기존 분석 stale 처리, outbox 발행
- 공통 오류 응답과 trace ID
- 공통 성공 응답과 안전한 trace ID 전달
- OpenAPI 3.1 계약
- Testcontainers 기반 실제 PostgreSQL 통합 테스트

OCR 공급자, OpenAI Vector Store/RAG, 객체 저장소, outbox worker와 보존 배치는 인터페이스 구현 전 단계입니다.

## 기술 기준

- Java 21 (record·text block 사용)
- Spring Boot 4.1
- Gradle Wrapper
- PostgreSQL 16 / pgvector
- Flyway
- Spring JDBC
- Spring Security OAuth2 Resource Server
- Testcontainers

## 로컬 실행

`.env.example`을 복사한 뒤 세 데이터베이스 비밀번호를 각기 다른 로컬 값으로 변경합니다. 예제 값을 운영 환경에 사용하지 않습니다. 비밀번호가 비어 있으면 애플리케이션과 PostgreSQL 컨테이너는 기동에 실패합니다.

```powershell
Copy-Item .env.example .env
docker compose up -d postgres
.\gradlew.bat bootRun
```

헬스 체크:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

애플리케이션 API는 UUID 형식의 JWT `sub`가 필요합니다. 로컬 인증 공급자 또는 테스트용 JWKS 주소를 `.env`에 설정합니다.
JWT는 RS256 서명, `exp`/`nbf`, `iss`, `aud`를 모두 검증합니다. `aud`에는 `JWT_AUDIENCE` 값이 포함되어야 하며 사용자 식별에는 요청 본문이나 쿼리의 user ID가 아닌 JWT `sub`만 사용합니다.

법령·판례 검색은 [국가법령정보 공동활용](https://open.law.go.kr/) 승인 후 발급 기준에 맞는 `LAW_OPEN_DATA_OC`를 `.env`에 설정합니다. 값이 없어도 서버와 다른 기능은 기동되며, 법률 데이터 API 호출만 `503 INTEGRATION_NOT_CONFIGURED`를 반환합니다.

## 검증

```powershell
.\gradlew.bat clean test
docker compose config
```

통합 테스트는 실제 `pgvector/pgvector:pg16` 컨테이너를 띄우고 다음을 확인합니다.

1. V001~V011 migration 전체 성공
2. 사용자 A의 사건 생성
3. 진술 변경 시 version 증가
4. 같은 트랜잭션의 outbox 2건
5. 사용자 B의 사건 조회 차단

## 중요한 보안 경계

- `legal_ai_migrator`: DDL owner이자 migration 전용 role
- `legal_ai_auth`: 인증 테이블 전용 role; casework 권한 없음
- `legal_ai_app`: API role; `NOBYPASSRLS`
- API repository 호출은 반드시 `UserScopedTransaction` 안에서 수행
- 사건이 없거나 타인 소유이면 모두 404로 처리
- 원본 파일 장기 보관 금지; 확정 OCR 수정본만 영속 보관
- 비밀값·원문·복호화 개인정보를 로그에 기록하지 않음

## 선행 안전 정책

- JWT는 RS256 서명, 만료·활성시간, 발급자, audience를 검증
- 로컬 비밀번호는 cost 12 BCrypt 형식만 허용
- refresh token은 원문 대신 소문자 SHA-256 해시만 저장
- 파일명에 `/` 또는 `\\` 경로 문자가 있으면 DB에서 거부
- 원본 파일은 24시간 내 삭제 대상으로 관리하고 확정 OCR 수정본을 영속 보관
- 법률 분석은 주택 임대차 범위로 제한하며 검색 근거가 없으면 확인 필요로 반환
- OCR·사용자 진술·검색 문서 내부의 명령은 신뢰하지 않는 데이터로 처리
- RAG 검색 결과 8건, 모델 출력 1,200토큰, 비동기 작업 재시도 5회로 제한
- Flyway clean 비활성화 및 migration checksum 검증

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
   ├─ serviceimpl/                 Service implementation
   ├─ repository/                  JDBC Repository
   ├─ entity/                      DB 조회 모델
   └─ dto/
      ├─ request/                   API 요청 DTO
      └─ response/                  API 응답 DTO
└─ legaldata/
   ├─ controller/                  법령·판례 REST Controller
   ├─ service/                     법률 데이터 Service interface
   ├─ serviceimpl/                 외부 응답 정규화 구현
   ├─ repository/                  법제처 Open API client
   ├─ entity/                      지원 문서 유형
   └─ dto/response/                정규화 응답 DTO
```

요청 흐름은 `Controller → Service → ServiceImpl → Repository → PostgreSQL`로 고정합니다.

## 현재 REST API

- `POST /api/v1/cases` — 사건 생성
- `GET /api/v1/cases/{caseId}` — 사건 단건 조회
- `PATCH /api/v1/cases/{caseId}` — 사건 진술 수정
- `GET /api/v1/legal-data/law?query=민법` — 법령 검색
- `GET /api/v1/legal-data/precedent?query=임대차%20수선의무` — 판례 검색
- `GET /api/v1/legal-data/{type}/{externalId}` — 법령·판례 본문 정규화 조회

## 다음 구현 순서

1. 인증 어댑터와 사용자 consent
2. 업로드 세션·malware scan·OCR outbox worker
3. OCR 수정본 확정과 원본 purge
4. 확인 질문·사실 충돌 해결
5. 법률 문서 적재·구조 기반 청킹·OpenAI Vector Store 연동
6. RAG 검색과 분석 실행
7. 대응 계획·문서 생성
8. 보존기간·탈퇴 purge job
