# Better Call AI Backend

개인 사용자가 자신의 생활 분쟁을 정리하고, OCR 수정본·법률 근거·AI 분석·대응 문서를 한 사건 단위로 관리하는 API 서버의 개발 기반입니다.

## 현재 구현 범위

- PostgreSQL 16 + pgvector 전체 Flyway migration V001~V019
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
- 사건별 채팅 질문 선저장, OpenAI 전체 응답 저장 후 반환, 멱등 재전송·명시적 재시도
- OpenAPI 3.1 계약
- Testcontainers 기반 실제 PostgreSQL 통합 테스트

OpenAI OCR 실행·조회, 수정본 이력·확정과 확정 후 임시 원본 삭제를 구현했습니다. OpenAI Vector Store/RAG, 객체 저장소, outbox worker와 DB 영구 삭제 배치는 구현 전 단계입니다.

### OpenAI OCR 및 수정본 확정

검색·분석 입력 연결의 첫 단계로 `GET /api/v1/cases/{caseId}/confirmed-evidence`를 제공한다. 내 사건의 현재 확정 텍스트만 `fileId`, `revisionId`, `confirmedAt`과 함께 반환하며, 기계 인식 원문·미확정 초안·과거 확정본·제거된 파일을 포함하지 않는다. 원본 삭제 후에도 확정 텍스트는 조회된다. 아직 외부 AI나 법률 검색을 호출하는 API는 아니다.

선택한 확정본으로 검색하려면 `POST /api/v1/cases/{caseId}/files/{fileId}/legal-evidence/search`를 사용한다. 본문 예시는 `{"query":"수리비 상환 근거를 찾아주세요","expectedCaseVersion":2,"excerptStart":0}`이다. 질문은 최대 300자이고 사건 버전은 필수다. `excerptStart` 생략/null은 0이며 확정 수정본에서 최대 650 UTF-16 코드 단위를 발췌한다. 이모지의 서로게이트 쌍 중간에서 시작하면 400으로 거부하고 끝 경계는 문자를 보존하도록 줄인다.

검색 응답은 `caseId`, `caseVersion`, `evidence`(파일·수정본 ID, 사용한 발췌문, 시작·끝·전체 길이, `partial`), `results`(공통 페이지 형식의 최대 8개 근거 후보)를 포함한다. `partial=true`이면 문서 앞이나 뒤에 사용하지 않은 내용이 있으므로 **전체 문서를 검색한 결과로 표시하면 안 된다.** 다른 부분은 같은 버전과 다른 `excerptStart`로 검색할 수 있다. 이번 MVP는 파일 1개 발췌를 사용하며 여러 파일의 자동 선택·전체 문서 요약·진술 자동 추가는 하지 않는다.

호출 전 소유권·사건 버전·현재 확정본을 확인하고 짧은 DB 트랜잭션을 끝낸 뒤 질문과 발췌문을 OpenAI 임베딩으로 전송한다. 미확정 초안·기계 OCR·원본 파일은 보내지 않는다. 외부 호출 후 다시 버전과 현재 수정본을 확인하며 변경은 409 `CASE_002`, 삭제된/타인 사건은 404 `CASE_001`, 사용 가능한 현재 확정본이 없는 파일은 404 `FILE_001`이다. 폐기된 검색에도 외부 호출 비용이 발생할 수 있다. 원본 삭제 후에도 사용할 수 있고 요청·발췌·결과는 로그나 별도 검색 기록에 저장하지 않는다. 기존 검색의 동시 처리 한도를 공유한다. 응답의 버전은 마지막 확인 시점 기준이며 이후 사건 변경까지 막지는 않는다.

응답은 `data.caseId`, `data.caseVersion`, `data.evidence`(공통 페이지 형식)다. `page`는 1~10000, `pageSize`는 1~100(기본 20), 정렬은 확정 시각·파일 ID 내림차순이다. 다음 페이지에는 첫 응답의 `caseVersion`을 `expectedCaseVersion`으로 보내면 변경 시 409 `CASE_002`로 차단한다. 새 초안 저장만으로는 확정본이 바뀌지 않으며, 새 확정 시 버전이 증가한다. 소유권·삭제 여부·버전과 본문을 짧은 단일 트랜잭션에서 확인한다.

기존 `OPENAI_API_KEY`를 공유한다. `OPENAI_OCR_MODEL`을 설정하지 않으면 `OPENAI_CHAT_MODEL`을 사용하며, 이미지와 PDF 입력을 지원하는 모델이어야 한다. 빈 값으로 별도 설정하면 미설정 오류가 발생한다. `OCR_PROVIDER`는 이 경로에서 사용하지 않는다. 외부 키가 없어도 앱 시작과 외부 연동 외 기능은 사용할 수 있다.

2026-09-10 실제 호출 평가에서 가상 문서 8건의 핵심 항목 69개와 공개 표준계약서 2페이지의 선정 문구 29개를 확인했다. 평가 설정은 `OPENAI_OCR_MODEL=gpt-5.6-sol`, `OPENAI_OCR_REASONING_EFFORT=none`, `OPENAI_OCR_TIMEOUT_SECONDS=150`이다. 표준계약서는 기존 90초 제한에서 실패 사례가 있어 최대 150초를 허용했다. 이는 실제 촬영 문서 전체의 정확도 보증이 아니다. [평가 결과·한계·재실행 방법](docs/ocr-accuracy-evaluation.md)을 참고한다.

후속 실제 영수증 평가에서는 한자 혼합 메뉴명을 한글로 치환하는 오류가 반복됐다. 문자 체계 보존 지침을 보완하고 가상 평가를 9건(핵심 76개)으로 늘렸지만, 실제 자료의 반복 오류까지 해소되지는 않았다. 사용자 검수를 거쳐 확정한 수정본만 다음 단계에 사용한다.

채팅 시스템 프롬프트는 주택 임대차 사실 정리, 상충·누락 정보 확인, 자료 준비 안내로 제한한다. `OPENAI_CHAT_REASONING_EFFORT`(기본 빈 값: 옵션 생략)와 `OPENAI_CHAT_MAX_OUTPUT_TOKENS`(기본 1200, 허용 1200~16000)로 OCR과 별도로 설정한다. 출력 예산에는 추론 토큰도 포함되며 모델별 지원값 확인이 필요하다. [프롬프트·설정·검증 기준](docs/ai-prompt-policy.md)을 참고한다. 확정 OCR·법률 검색은 아직 채팅 입력에 연결되지 않았다.

`OPENAI_OCR_REASONING_EFFORT`를 비우면 API 요청에서 생략한다. 지정할 때는 해당 모델이 지원하는 값이어야 한다. 예제의 `none`은 평가한 Sol용이며, 추론 옵션을 지원하지 않는 모델로 변경하면 이 값을 비운다. timeout 기본값은 90초이며 최대 150초로, 3분 실행 예약보다 짧다. 앱 실행 시 이 환경 변수를 전달해야 하며 Spring이 `.env`를 자동으로 읽는 것은 아니다.

모든 경로는 `/api/v1/cases/{caseId}/files/{fileId}` 기준이며 소유자 JWT가 필요하다. 다른 사용자·사건·삭제된 사건의 파일은 404다.

| 요청 | 입력 | 결과 |
| --- | --- | --- |
| `POST /ocr` | UUID `Idempotency-Key` 헤더, `{"externalOcrAccepted":true}` | 동기 OCR 실행 및 결과, 200 |
| `GET /ocr` | 없음 | 처리 상태, 기계 인식 원문, 최신 수정본, 확정본 |
| `POST /ocr-revisions` | `extractionId`, `expectedRevision`, `correctedText` | 새 수정본, 201 |
| `GET /ocr-revisions` | `page` 기본 1, `pageSize` 기본 20 | 공통 페이지 응답, 최신순 |
| `POST /confirm` | `revisionId`, `sensitiveDataReviewed:true` | 확정 수정본, 200 |

- OCR 시작은 원본의 OpenAI 전송 동의를 요구하고 동의 기록을 남긴다. 검사 완료된 유효한 임시 원본만 읽고 길이·SHA-256을 재확인한다. PNG/JPEG/WebP는 `input_image`, PDF는 `input_file`로 Responses API에 base64 전송한다. Files API에 별도 업로드하지 않고 `store:false`를 사용한다. 이는 OpenAI 측 무보관을 보장하는 옵션은 아니며, 여기서의 원본 삭제는 서비스의 임시 저장소를 뜻한다.
- 파일당 한 번의 성공 OCR을 허용한다. 같은 키 재요청은 저장 결과를 반환하며 외부 호출하지 않는다. 실행 중 요청은 429(`OCR_004`), 실패한 키 재요청은 503(`OCR_003`)이다. 실패 시 결과를 조회한 뒤 사용자가 명시적으로 새 UUID로 재시도한다. 자동 재호출하지 않으며 재시도에는 추가 비용이 발생할 수 있다. 성공 후 다른 키는 409(`OCR_002`)다.
- 실행 전 결과 조회는 409(`OCR_001`), 실행 중·실패 상태 조회는 200이며 `rawText`는 null이다. 연동 미설정은 503(`COMMON_002`), 만료·변조·삭제된 원본은 409(`OCR_005`)다. 처리 중 원본이 만료되면 늦게 도착한 결과를 저장하지 않는다. 프로세스 중단으로 3분 이상 남은 실행은 다음 조회/새 요청에서 실패 처리한다. 자동 복구 worker는 없다.
- 외부 호출 중 DB 연결과 행 잠금을 유지하지 않는다. 서버 인스턴스당 최대 2건, 파일당 1건을 처리한다. 파일당 기존 20 MiB 제한을 유지하며, 응답 대기는 기본 90초·설정 상한 150초, 연결은 3초, 출력은 최대 16,000토큰·100,000자·응답 본문 1 MiB다. 미완료·거절·비정상 응답은 성공 원문으로 저장하지 않는다. OCR 응답 ID·모델·입출력 토큰은 채팅과 별도로 기록한다. 무료 업로드 횟수 정책을 변경하거나 유료 일일 제한을 추가하지 않는다.
- 최초 수정은 `expectedRevision:0`, 이후에는 화면에서 읽은 최신 revision 번호를 보낸다. 공백뿐인 본문·NUL은 거절하며 최대 100,000자, 최대 10,000개 수정본이다. 같은 기대 번호와 본문의 재전송은 기존 수정본을 반환한다. 동시 수정 충돌은 409다. 기계 인식 원문은 덮어쓰지 않는다.
- 최신 수정본만 확정할 수 있다. 민감정보 확인 플래그는 사용자 확인이며 자동 마스킹을 뜻하지 않는다. 확정 시 사건 버전을 올리고 기존 분석을 `ocr_changed`로 무효화하며 `OCR_CONFIRMED` 이벤트를 기록한다. 같은 확정 요청을 반복해도 버전·이벤트가 중복 증가하지 않는다. 새 초안을 저장해도 다음 확정 전까지 기존 확정본은 유지된다.
- 확정 트랜잭션 커밋 후 원본을 삭제한다. 삭제 실패에도 확정 텍스트는 유지하며 기존 정리 배치가 재시도한다. 확정 시 만료 시각을 앞당겨 커밋 직후 프로세스가 종료돼도 정리 대상에 포함한다. 원본 삭제 후에도 OCR 조회·수정·확정이 가능하다.
- 이번 단계는 백엔드 OCR 검토 흐름이다. 원본 미리보기, 자동 개인정보 탐지, 확정 텍스트의 채팅·RAG 연결은 후속 작업이다. 클라이언트는 OCR 텍스트를 HTML로 직접 삽입하지 않는다. 테스트는 로컬 HTTP 고정 응답과 PostgreSQL을 사용하며 실제 문서의 인식 정확도는 별도 확인이 필요하다.

## 채팅 MVP: 완성 답변 저장 후 표시

현재 채팅은 **법률 판단이 아니라 사건의 사실 정리·추가 질문 단계**입니다. RAG 근거가 없어 법령·판례·기한·책임 판단을 생성하지 않도록 지시합니다. 프롬프트만으로 환각이나 인젝션을 완전히 차단할 수는 없습니다. 공식 근거 검색·인용 검증·법률 답변 평가가 붙기 전에는 법률 상담 기능으로 출시하지 않습니다.

1. `POST /api/v1/cases/{caseId}/chat/turns`: `Idempotency-Key: <UUID>`, 본문 `{"content":"누수가 발생했어요"}`. 서버는 JWT 소유권 확인 후 질문을 먼저 커밋합니다.
2. DB 연결과 행 잠금을 반환한 뒤 OpenAI Responses API를 비스트리밍 호출합니다. 완성 답변 저장 커밋 이후에만 HTTP 200으로 질문·답변을 반환합니다. 부분 답변은 반환·저장하지 않습니다.
3. `GET /api/v1/cases/{caseId}/chat/turns?page=1&pageSize=20`: 최신 턴부터 반환합니다. 각 턴은 `id, question, answer, attempt, retryAllowed, createdAt, answeredAt`입니다. 목록 최대 100개/페이지이며 화면에서는 역순으로 배치합니다. `answer=null`이면 질문만 표시하고 내부 상태 문구는 표시하지 않습니다.
4. 프론트는 완성된 `answer` 문자열을 메모리에서 나누어 타이핑 효과로 렌더링하면 됩니다. 글자별 API 호출, Redis, SSE는 사용하지 않습니다. 타이핑 효과는 연출이며 첫 답변 대기 시간을 단축하지 않습니다. HTML을 그대로 삽입하지 말고 안전한 텍스트/정제된 Markdown으로 표시합니다.

### 중복·실패·재시도 계약

- 같은 사건에서 같은 UUID와 동일한 앞뒤 공백 제거 질문을 재전송하면 저장된 답변을 반환합니다. 다른 질문이면 409 `CHAT_001`입니다. 전송 재시도 때 UUID를 새로 만들지 않습니다.
- 완료 전 중복 요청 또는 서버 보호 제한은 429 `CHAT_002`입니다. UI에는 내부 RUNNING 등의 상태를 채팅 답변으로 추가하지 않습니다.
- AI 실패는 503 `CHAT_003`, 답변 저장 확인 실패는 503 `CHAT_005`입니다. 외부 오류 원문·대체 법률 답변을 반환하지 않고 일반 오류 안내만 보여 줍니다.
- HTTP 연결이 끊기거나 503/429를 받으면 먼저 목록을 조회합니다. 정상 실행이 계속되면 저장된 답변을 이후 조회할 수 있지만 프로세스 종료까지 작업 완료를 보장하는 백그라운드 큐는 아닙니다.
- 마지막 질문의 `retryAllowed=true`일 때만 재시도 버튼을 표시합니다. `POST /api/v1/cases/{caseId}/chat/turns/{id}/retry`, 본문 `{"expectedAttempt":1}`처럼 **조회한 시도 번호**를 보냅니다. 서버가 번호를 증가시키며 질문은 복제하지 않습니다. 같은 재시도 요청 재전송도 새 과금을 일으키지 않습니다. 더 최신 질문이 있으면 이전 실패 턴은 재시도하지 못합니다.
- 재시도 결과까지 실패했다면 다시 목록을 조회한 뒤 새 `attempt`를 사용합니다. 자동으로 번호를 올리며 반복 호출하면 안 됩니다. 새로운 명시적 재시도는 추가 과금될 수 있습니다.
- 3분 이상 남은 내부 실행은 해당 사용자의 다음 목록 조회·전송·재시도에서 실패로 전환합니다. 자동 재생성하지 않으며 이전 시도의 늦은 답변 저장은 실행 세대/상태/기한 검사로 차단합니다. 접근이 없는 사용자의 상태를 주기적으로 정리하는 배치는 아직 없습니다.
- 사건 삭제 시 채팅도 즉시 소유권 조회에서 숨기고, 사건 영구 삭제 시 FK CASCADE로 제거합니다. 실행 도중 사건이 삭제되면 답변을 반환하지 않습니다. DB 영구 삭제 배치는 기존과 같이 미구현입니다.

### 설정과 서버 보호

- 실행 환경에 `OPENAI_API_KEY`, `OPENAI_CHAT_MODEL`을 설정합니다. 모델 기본값은 비워 두었으며 사용할 수 있는 Responses 호환 모델 ID를 명시해야 합니다. 키/모델이 없으면 채팅 신규 실행만 503 `COMMON_002`이며 기존 답변 조회와 다른 기능은 동작합니다. 실제 키는 `.env.example`이나 Git에 넣지 않습니다.
- 인스턴스당 AI 호출 최대 2개, 사용자당 활성 턴 1개입니다. 사용자 제한은 DB 잠금으로 여러 서버에 걸쳐 적용되고 인스턴스 제한은 서버 수만큼 늘어납니다. OpenAI 프로젝트 전체 RPM/TPM 한도를 조정하는 전역 제한기는 아직 없습니다.
- 질문 최대 4,000자, 최근 완성된 최대 5턴과 현재 질문 합계 최대 16,000자, 출력 최대 1,200토큰, 답변 최대 16,000자, 외부 응답 최대 512 KiB입니다. 시스템 지침은 기본 답변 600자 이내를 목표로 결론·필요한 사실·다음 행동만 쓰고 반복을 피하도록 합니다. 600자는 프롬프트 목표이며 강제 절단하지 않습니다. 불확실성과 안전 안내는 유지하고, 토큰 한도로 미완료된 응답은 기존 실패 처리하며 자동 이어쓰기를 하지 않습니다. 프롬프트는 입력 비용이나 총 과금 상한을 보장하지 않습니다. 문맥은 오래된 턴부터 제외하며 전체 사건의 모든 정보를 기억하는 구조가 아닙니다. 인증 이메일·원본 파일·OCR·사건 진술은 자동으로 보내지 않습니다. 사용자가 직접 쓴 개인정보는 포함될 수 있으므로 입력 전 안내·추가 비식별화는 출시 전 점검해야 합니다.
- 연결 3초, 개별 호출 전체 수신 최대 40초입니다. 429/5xx만 최대 1회 재시도하며 `Retry-After`가 2초를 넘거나 HTTP-date이면 자동 재시도를 중단합니다. 타임아웃·연결 오류·400/401·잘린 응답·거절은 자동 재시도하지 않습니다. 인프라 HTTP 타임아웃은 최장 약 82초의 생성 시간과 DB 처리 여유를 고려해야 합니다.
- 답변 DB 저장만 최대 1회 재시도하며 모델은 다시 호출하지 않습니다. 클라이언트 전송 중복 방지와 공급자 과금의 exactly-once 보장은 다릅니다. 최종 성공 응답의 모델·응답 ID·입출력 토큰만 기록하므로 실패·재시도 비용까지 포함한 청구 원장은 아니며 공급자 사용량과 대조해야 합니다.
- 채팅 제한은 파일 업로드 FREE/PAID 일일 정책과 독립적입니다. 유료 일일 300회 등의 상품 제한을 새로 추가하지 않았습니다.
- `store:false`로 Responses 객체 보관을 끄지만 모든 공급자 로그/보관 정책의 제로 보존을 뜻하지 않습니다. 본문·토큰·외부 오류 원문을 애플리케이션 로그에 남기지 않습니다.

OpenAI 연동 계약은 공식 [텍스트 생성 문서](https://developers.openai.com/api/docs/guides/text)와 [대화 상태 관리 문서](https://developers.openai.com/api/docs/guides/conversation-state)를 기준으로 작성했습니다. 테스트는 로컬 HTTP 서버와 모의 모델 응답을 사용하며 실제 유료 API를 호출하지 않습니다.

## 법률 데이터 분류

법률 데이터 분류는 다음 기준을 사용합니다.

- `legal_documents.document_type`: `law`, `precedent` 등 문서 종류. 기존 값 유지.
- `legal_documents.law_kind`: 공식 법령 형식. `CONSTITUTION`(헌법), `ACT`(법률), `PRESIDENTIAL_DECREE`(대통령령), `PRIME_MINISTER_ORDINANCE`(총리령), `MINISTERIAL_ORDINANCE`(부령), `RULE`(규칙). 비법령 문서는 설정 불가. 미확인·미지원 분류는 `NULL`이며 기존 문서를 일괄 추정·보정하지 않습니다.
- `title`: 민법·주택임대차보호법 등 개별 법령명. 공식 ID·버전·시행일과 함께 관리합니다.
- `legal_chunks.metadata.topic_tags`: 조문별 검색 주제. 예: `["housing_lease", "repair_duty"]`. 문자열 배열만 허용하고 null·숫자·공백 태그는 차단합니다. 미분류는 키 생략 또는 빈 배열이며, 민법 전체에 임대차 태그를 자동 전파하지 않습니다. 태그 검색용 GIN 인덱스를 제공합니다.
- 법령 검색·본문 API에 nullable `lawKind`를 추가했습니다. 목록의 `법령구분명`, 본문의 `기본정보.법종구분`으로 매핑하며 법령 제목이나 참조 조문의 분류를 대신 사용하지 않습니다.

분류 스키마와 조회 응답 매핑에 더해 아래 운영 명령으로 원문 버전별 적재·조문 분할·태그 부여·임베딩을 실행할 수 있습니다. 서버 기동 시 자동으로 자료를 수집하지 않습니다.

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

1. V001~V019 migration 전체 성공
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
- `GET /api/v1/legal-data/precedent?query=임대차%20수선의무` — 판례 본문 검색 (`search=2`). 법령 검색과 판례 단건 본문 조회에는 이 옵션을 적용하지 않습니다. [공식 검색 범위](https://open.law.go.kr/LSO/openApi/guideResult.do?htmlName=precListGuide)
- `GET /api/v1/legal-data/{type}/{externalId}` — 법령·판례 본문 정규화 조회

## 다음 구현 순서

1. PDF·이미지 파싱을 별도 제한 프로세스로 격리
2. OCR 원본 검토 화면 연결과 확정 수정본의 검색·분석 연동
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
- 원본 다운로드는 후속 작업이다. OCR 실행·수정본 확정·확정 후 원본 삭제는 아래 API로 제공한다. 인터넷 공개 전 별도 프로세스 파싱 격리와 순간 요청 속도 제한을 보완해야 한다.

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

## 공식 법령 수집·조문 분할·임베딩 (로컬 운영 명령)

전용 PostgreSQL은 `127.0.0.1:5433/legal_ai`를 사용한다. 다른 프로젝트의 5432 DB는 사용하지 않는다.
`.env`의 `DATABASE_URL`, `DATABASE_MIGRATION_USER`, `DATABASE_MIGRATION_PASSWORD`, `LAW_OPEN_DATA_OC`를 설정한다.
임베딩과 검색 점검에는 `OPENAI_API_KEY`도 필요하다. 키·원문 데이터·벡터는 Git에 올리지 않는다.

```powershell
docker compose up -d postgres
.\gradlew.bat importLegalData --args=collect
.\gradlew.bat importLegalData --args=embed
.\gradlew.bat importLegalData --args=verify
# 아래 명령은 고정 예시 질문 1건을 추가 임베딩하므로 과금된다.
.\gradlew.bat importLegalData --args=search-check
```

- `collect`: 기존 Flyway 마이그레이션 적용 후 현행 법령 3개를 수집한다. 제목과 공식 ID를 함께 대조하고 목록의 MST·시행일에 해당하는 본문을 가져온다. OpenAI는 호출하지 않는다.
- `embed`: `text-embedding-3-small`의 1,536차원 벡터를 `knowledge.chunk_embeddings`에 저장한다. 시스템 프롬프트 없이 공개 조문만 보낸다. 한 요청 최대 16조각, 각 입력 최대 6,000 UTF-8 바이트이며 원문 전체를 임베딩하지 않는다.
- `verify`: 적재·선별·임베딩 개수를 조회한다. 외부 API는 호출하지 않는다.
- 명령은 저장소 루트의 `.env`를 직접 읽으며 localhost/127.0.0.1의 5433 `legal_ai` DB만 허용한다. 별도 운영 연결과 DB 잠금으로 명령 동시 실행을 차단한다. 웹 API나 자동 스케줄러는 추가하지 않았으며 일반 앱 역할의 지식 DB 읽기 전용 권한도 유지한다.

### 2026-09-09 실제 적재 결과

| 법령 | 공식 버전(MST) | 시행일 | 저장 조각 | 임베딩 조각 |
|---|---|---|---:|---:|
| 민법 | 284415 | 2026-03-17 | 1,232 | 44 |
| 주택임대차보호법 | 276291 | 2026-01-02 | 67 | 41 |
| 주택임대차보호법 시행령 | 287183 | 2026-07-01 | 67 | 36 |
| 합계 | | | 1,366 | 121 |

데이터는 이 개발 PC의 Docker PostgreSQL 볼륨에 있다. Git을 받은 다른 개발자는 위 명령으로 자신의 DB에 수집해야 한다.

### 분할·비용·갱신 정책

- 법령 JSON 전체(부칙·편/장 제목 포함)는 `raw_text`에 보존한다. 조문은 항·호·목 순서와 본문을 유지하고 긴 내용만 5,500 UTF-8 바이트 단위로 추가 분할한다. 바이트 수는 정확한 토큰 수가 아니므로 `token_count`를 추정값으로 채우지 않는다.
- 조문 번호·가지번호·공식 조문키·시행일·원문 해시·분할 순서·파서 버전을 메타데이터에 저장한다. 부칙의 중첩 텍스트 배열도 순서대로 보존한다.
- 초기 선별은 민법 제618~654조와 제390·393·536·543·544·548·550·580조, 주택임대차보호법 및 시행령 본문이다. 삭제·미시행 조문과 부칙은 초기 임베딩에서 제외한다. `topic_tags=["housing_lease"]`로 선별하며 `law_kind`는 법률/대통령령 등 법령 형식이다.
- 같은 공식 ID·버전은 다시 삽입하지 않는다. 같은 버전의 원문 해시나 분할 개수가 달라지면 덮어쓰지 않고 중단한다. 새 버전은 별도 저장하고 이전 버전을 비현행으로 표시한다. 파서·선별 규칙 변경 시 기존 데이터를 자동 재구성하지 않으므로 별도 검토가 필요하다.
- 같은 조각·모델·해시로 저장된 임베딩은 재호출하지 않는다. 신규 법령 버전의 조각은 별도 대상이다. 성공 배치는 즉시 트랜잭션으로 저장하며 실패 시 중단한다. API 자동 재시도는 하지 않는다.
- **API 성공 후 DB 저장 전 중단된 배치는 재실행 때 다시 과금될 수 있다.** 완전한 외부 과금 멱등성을 보장하는 구조는 아니다. 오류·타임아웃 후에는 저장 현황을 먼저 확인한다. 연결 3초·전체 응답 30초·응답 본문 2 MiB 제한을 적용한다.
- 임베딩 요청은 총 8배치로 121개를 저장했고 검색 점검에 질문 1건을 추가 호출했다. 실제 사용 토큰·금액은 OpenAI 사용량 화면에서 확인한다. 현재 수집 명령은 비용 원장을 저장하지 않는다.

### 검색 검증의 한계와 다음 단계

pgvector 코사인 검색과 공식 출처 반환은 동작한다. 하지만 수리비 반환 예시 질문의 상위 5건에 민법 제623·626조가 포함되지 않았다. 두 조문의 원문 적재는 확인했으므로 **현재 벡터 단독 검색 품질은 법률 답변용으로 검증되지 않았다.** 검색 점수는 법률적 신뢰도가 아니다.

인증된 혼합 검색 API를 추가했다. 아래 설명처럼 검토 후보를 반환하며 법률 답변 품질 검증은 별도다. 부칙의 경과규정과 과거 사건 적용 법령, 판례 데이터, 인용 검증도 별도 작업이다. 기존 채팅에는 아직 RAG 검색을 연결하지 않았으며 사실 정리 전용 안전 지침을 유지한다.

### 주택 임대차 근거 후보 검색 API

`POST /api/v1/legal-evidence/search`에 소유자 구분과 무관한 인증 사용자 JWT와 `{"query":"수선 비용 반환"}`을 전달한다. 검색어는 공백 제외 필수, 최대 1000자다. 검색어를 OpenAI `text-embedding-3-small`로 한 번 임베딩하므로 비용이 발생할 수 있다. 쿼리를 URL에 넣거나 요청·결과를 저장하지 않는다. 사건·OCR 본문도 자동 전송하지 않는다.

- 대상: 공식 수집 소스의 현행·시행 중 법령에서 `housing_lease` 태그가 붙은 미삭제·시행 중 본문 조각. 해당 모델과 원문 해시가 일치하는 임베딩만 사용한다.
- 방식: 주택 관련 질문에 수선·필요비 상환·차임 감액·임차권등기명령·묵시적 갱신 관련 검색어를 규칙에 따라 추가한다. 같은 보완문으로 벡터 코사인 거리 상위 20개와 2자 이상 단어 부분 일치 개수 상위 20개를 RRF(`k=60`)로 결합한다. 동점은 조각 UUID로 정렬한다. 형태소 분석·BM25는 아니며 특정 조문을 강제 삽입하지 않는다. 추가 용어는 사실 확정이나 법률 결론이 아니다.
- 응답: 공통 `data.items`에 최대 8개 후보의 조각/문서 ID, 법령명, 조문 제목, 본문, 공식 원문 URL, 버전, 시행일, 순위 점수. `page=1`, `pageSize=8`, `hasNext=false`의 단일 상위 결과 집합이다.
- 한계: 순위 점수는 관련성 확률이나 법률적 신뢰도가 아니다. 관련성이 낮아도 가장 가까운 후보가 반환될 수 있다. 현행 여부는 마지막 수집 상태 기준이며 실시간 법 개정 확인이나 사건 당시 적용 법령 판단을 하지 않는다. 판례는 아직 미포함이다.
- 보호: 인스턴스당 동시 검색 2개, 임베딩 자동 재시도 없음, API 대기 중 DB 연결 미점유. 잘못된 입력 400, 미인증 401, 동시 한도 429 `LEGAL_DATA_003`, 키 미설정 503 `COMMON_002`, 임베딩/DB 실패 503 `LEGAL_DATA_002`.

`importLegalData --args=search-check`는 고정 수리비 질문의 원문과 용어 보완문을 함께 임베딩하여 기존 벡터 상위 5개와 보완 혼합 상위 8개를 출력한다. `search-evaluate`는 고정 질문 14개를 비교하는 명시적 실제 API 평가다. [평가 결과와 한계](docs/legal-search-evaluation.md)를 참고한다. 일반 테스트는 실제 OpenAI를 호출하지 않고 고정 벡터와 PostgreSQL로 정렬·선별 조건을 검증한다. 순위 결합의 참고 자료는 [pgvector 공식 문서](https://github.com/pgvector/pgvector#hybrid-search)다.

용어 보완 전인 2026-09-10 재검증에서 개발 DB의 121개 임베딩을 확인했다. 동일 수리비 질문의 벡터 상위 5개는 주택임대차보호법 제3조·제3조의4, 민법 제624조·제621조, 주택임대차보호법 제9조였다. 혼합 검색 상위 8개는 민법 제634조, 주택임대차보호법 제6조의2, 민법 제624조, 주택임대차보호법 제3조·제3조의4·제6조의3, 민법 제621조·제649조였다. **두 방식 모두 기존 목표 조문 제623·626조를 찾지 못했으며 정확도 개선을 입증하지 못했다.** 이 한 질문은 전체 검색 품질 평가가 아니다. API는 검토 후보 조회 단계로 제공하며, 법률 답변 연결 전 일상어·법률 용어 차이와 고정 평가 질문군을 보완해야 한다.

공식 기준: [현행법령 목록 API](https://open.law.go.kr/LSO/openApi/guideResult.do?htmlName=lsEfYdListGuide), [OpenAI 임베딩 가이드](https://developers.openai.com/api/docs/guides/embeddings).

후속 용어 보완 평가에서 고정 질문 14개의 목표 조문 적중은 원문 5/15개에서 보완 후 15/15개로 늘었다. 추가 표현의 누락을 수정한 뒤 같은 질문을 재검증한 결과이므로 독립 평가나 실서비스 정확도 보장은 아니다. [전체 평가 기록](docs/legal-search-evaluation.md)에 수정 전후와 한계를 남겼다.

## 검색 근거 기반 안내 초안

`POST /api/v1/cases/{caseId}/files/{fileId}/legal-draft`는 사건별 검색과 같은 요청 본문(`query`, `expectedCaseVersion`, 선택적 `excerptStart`)을 받는다. 질문 300자·현재 확정본 1개의 최대 650자 발췌 제한을 유지한다. 본인 사건과 확정본을 확인한 뒤 근거 후보를 검색하고 구조화된 안내 초안을 만든다.

응답에는 사건 버전·사용 발췌문, `status`, 사실 요약 `summary`, 최대 3개의 `findings`(조건부 설명·원문 인용·서버가 연결한 공식 출처), 최대 3개의 `questions`, 검토 안내 `notice`가 포함된다. 출처 번호와 원문 인용이 일치하지 않거나 응답이 미완료·거부·형식 오류이면 503 `LEGAL_ANSWER_001`로 처리한다. 출처 일치는 법률적 타당성 보장이 아니다.

후보가 없으면 생성 호출 없이 `INSUFFICIENT_EVIDENCE`를 반환한다. 후보 중 관련 근거를 선택하지 못한 출력도 같은 상태다. 근거 항목이 있는 초안은 `NEEDS_REVIEW`다. 검색·생성 후 사건 버전과 수정본을 다시 확인하며 도중 변경은 409, 사건 삭제는 404로 초안을 폐기한다. 응답 이후 변경까지 막는 잠금은 아니다.

초안은 기존 채팅의 모델·추론·출력 토큰 설정을 공유하지만 별도 시스템 지침을 사용한다. 출력 예산이 부족해도 부분 JSON을 성공 처리하지 않는다. 초안 동시 실행은 인스턴스당 2개(초과 시 429 `LEGAL_ANSWER_002`)이며 생성 자동 재시도는 없다. 요청당 임베딩과 생성 비용이 발생할 수 있고 반복 요청은 새 호출이다. 분석 이력·채팅·모델 실행 원장에 저장하지 않는다. 기존 채팅은 사실 정리 전용으로 유지한다.

이번 검증은 로컬 응답과 고정 자료를 사용한 통합·출처 검증 테스트다. 실제 모델의 법률 답변 품질, 판례와 과거 적용 법령, 전체 문서 분석은 검증·구현 범위에 포함하지 않았다. [프롬프트와 설정 기준](docs/ai-prompt-policy.md)을 참고한다.
