# 무료 서버 배포 준비

이 프로젝트는 Docker 이미지 하나로 Spring 정적 화면과 API를 함께 제공한다. 서버는 `8080` 포트를 열고 `SERVER_PORT`로 플랫폼이 지정한 포트를 받을 수 있다.

배포 환경에서는 PostgreSQL(pgvector), Redis, ClamAV를 애플리케이션과 분리된 관리형 서비스 또는 별도 컨테이너로 준비한다. `DATABASE_URL`, `DATABASE_MIGRATION_USER`, `DATABASE_MIGRATION_PASSWORD`, `DATABASE_APP_USER`, `DATABASE_APP_PASSWORD`, `DATABASE_AUTH_USER`, `DATABASE_AUTH_PASSWORD`, JWT 키 3종, `IDENTITY_*` 키, `OPENAI_API_KEY`, `OPENAI_CHAT_MODEL`, `LAW_OPEN_DATA_OC`, `REDIS_URL`, `CLAMAV_HOST`, `CLAMAV_PORT`를 환경 변수로 주입한다. `.env` 파일과 원본 파일은 이미지에 복사하지 않는다.

배포 순서는 다음과 같다.

1. PostgreSQL에 `pgvector`를 활성화하고 빈 데이터베이스를 만든다.
2. Redis와 ClamAV의 사설 네트워크 주소를 환경 변수에 지정한다.
3. 이미지를 빌드해 무료 서버의 컨테이너 서비스에 배포한다.
4. `/actuator/health`가 200인지 확인한 뒤 회원가입·사건 생성·파일 업로드·OCR·분석의 축소 E2E를 실행한다.
5. 운영 키를 넣기 전에는 결제 UI를 노출하지 않는다. 결제 API는 `TOSS_SECRET_KEY`가 있을 때만 외부 승인을 시도한다.

무료 서버는 수면, 메모리·디스크·네트워크 제한과 임시 파일 손실을 가정한다. 영속 PostgreSQL·Redis·객체 저장소가 제공되지 않는 플랫폼에서 운영 데이터를 보관하지 않는다.
