-- Schema and table descriptions are maintained as a forward-only migration.
COMMENT ON SCHEMA identity IS '로그인 계정, 외부 인증 식별자, 사용자 동의 이력';
COMMENT ON SCHEMA casework IS '사용자 소유 사건, 진술, 파일, OCR, 증거와 사실 관계';
COMMENT ON SCHEMA knowledge IS '법령·판례·행정자료와 RAG 검색 청크';
COMMENT ON SCHEMA aiops IS 'AI 모델 호출, 검색, 분석 결과와 근거 추적';
COMMENT ON SCHEMA workflow IS '대응 계획과 사용자용 생성 문서';
COMMENT ON SCHEMA audit IS '보안 및 중요 상태 변경 감사 이력';
COMMENT ON SCHEMA ops IS '비동기 outbox와 런타임 정책 설정';

COMMENT ON TABLE identity.users IS '서비스 사용자 계정. 이메일은 암호문과 조회용 해시로 분리 저장한다.';
COMMENT ON TABLE identity.auth_identities IS '사용자와 local/OAuth 인증 공급자 subject의 연결 정보';
COMMENT ON TABLE identity.user_consents IS '약관, 개인정보, 외부 OCR, AI 처리에 대한 버전별 동의 이력';

COMMENT ON TABLE casework.cases IS '한 사용자가 소유하는 생활 분쟁 사건의 최상위 aggregate';
COMMENT ON TABLE casework.case_parties IS '사건 당사자와 사용자와의 관계';
COMMENT ON TABLE casework.files IS 'OCR 처리를 위한 임시 원본 파일 메타데이터와 파기 상태';
COMMENT ON TABLE casework.case_statements IS '사용자 진술과 후속 답변에서 정규화한 사건 서술';
COMMENT ON TABLE casework.case_events IS '사건의 시간순 사실 타임라인';
COMMENT ON TABLE casework.event_sources IS '타임라인 사건을 뒷받침하는 진술·OCR·답변·증거 출처';
COMMENT ON TABLE casework.file_extractions IS '파일별 OCR/layout/vision 실행 결과와 멱등 처리 단위';
COMMENT ON TABLE casework.ocr_text_revisions IS 'OCR 기계 원문을 보존하면서 사용자가 교정한 텍스트 버전';
COMMENT ON TABLE casework.extracted_fields IS 'OCR 결과에서 추출한 날짜, 금액, 인물 등 구조화 필드';
COMMENT ON TABLE casework.pii_findings IS '진술·OCR·생성 문서에서 발견한 개인정보 위치와 마스킹 상태';
COMMENT ON TABLE casework.fact_conflicts IS '서로 다른 출처에서 추출된 사실 값의 충돌과 사용자 해결 결과';
COMMENT ON TABLE casework.case_claims IS '사건에서 검토할 주장 또는 법적 쟁점';
COMMENT ON TABLE casework.evidence_items IS '주장을 뒷받침하거나 반박하는 증거 단위';
COMMENT ON TABLE casework.evidence_claim_links IS '증거와 주장의 지지·반박·맥락 관계';
COMMENT ON TABLE casework.case_questions IS '사실 누락과 충돌을 해결하기 위해 AI가 생성한 확인 질문';
COMMENT ON TABLE casework.case_answers IS '사용자가 확인 질문에 제공한 답변';

COMMENT ON TABLE knowledge.legal_sources IS '법령·판례·행정자료를 제공한 공식 원천';
COMMENT ON TABLE knowledge.legal_documents IS '시행일과 버전을 포함한 검색 대상 법률 문서';
COMMENT ON TABLE knowledge.legal_chunks IS '검색과 인용을 위한 법률 문서 청크';
COMMENT ON TABLE knowledge.chunk_embeddings IS '법률 청크의 모델별 vector embedding';

COMMENT ON TABLE aiops.prompt_versions IS '운영에서 재현 가능한 AI 프롬프트 버전';
COMMENT ON TABLE aiops.model_runs IS 'OCR·질문·검색·분석·문서 생성 모델 호출의 공통 실행 기록';
COMMENT ON TABLE aiops.analysis_runs IS '사건 버전별 종합 분석 실행과 stale 상태';
COMMENT ON TABLE aiops.analysis_run_inputs IS '분석 재현을 위해 고정한 입력 source와 hash';
COMMENT ON TABLE aiops.retrieval_runs IS 'RAG 검색 요청과 적용 필터';
COMMENT ON TABLE aiops.retrieval_results IS '검색 순위, 점수와 채택 여부';
COMMENT ON TABLE aiops.analysis_findings IS '책임, 리스크, 쟁점 등 구조화된 분석 판단';
COMMENT ON TABLE aiops.analysis_finding_sources IS '분석 판단과 사건 내부 근거의 연결';
COMMENT ON TABLE aiops.analysis_citations IS '분석 판단과 법률 문서 청크 인용의 연결';

COMMENT ON TABLE workflow.action_plans IS '현재 유효한 분석에서 생성한 사용자 대응 계획';
COMMENT ON TABLE workflow.action_items IS '대응 계획의 순서·기한·주의사항이 있는 실행 항목';
COMMENT ON TABLE workflow.generated_documents IS '내용증명, 메시지 등 사용자용 생성 문서의 버전';
COMMENT ON TABLE workflow.document_participant_snapshots IS '문서 생성 당시 당사자 정보 스냅샷';
COMMENT ON TABLE workflow.generated_document_sources IS '생성 문장과 분석·증거·법률 인용 근거의 연결';

COMMENT ON TABLE audit.audit_logs IS '로그인과 사건 중요 변경을 추적하는 append-only 감사 로그';
COMMENT ON TABLE ops.outbox_events IS 'DB 변경과 외부 OCR·AI·삭제 작업을 원자적으로 연결하는 outbox';
COMMENT ON TABLE ops.runtime_settings IS '업로드 한도, 보존기간, 호출량 등 운영 정책 값';

COMMENT ON COLUMN identity.users.email_enc IS '애플리케이션 envelope encryption으로 암호화한 이메일';
COMMENT ON COLUMN identity.users.email_lookup_hash IS '동일 이메일 검색과 중복 검사용 단방향 HMAC/해시';
COMMENT ON COLUMN identity.users.encryption_key_id IS 'email_enc 복호화에 사용하는 KMS key 식별자';
COMMENT ON COLUMN identity.auth_identities.provider_subject IS '인증 공급자가 발급한 변경 불가능한 사용자 식별자';
COMMENT ON COLUMN identity.auth_identities.password_hash IS 'local 인증에만 사용하는 비밀번호 해시. 평문 저장 금지';

COMMENT ON COLUMN casework.cases.owner_user_id IS '사건을 단독 소유하는 사용자 ID이자 RLS 기준';
COMMENT ON COLUMN casework.cases.version_no IS '사건 입력 변경 시 증가하는 optimistic locking 버전';
COMMENT ON COLUMN casework.cases.current_analysis_run_id IS '현재 화면에 노출할 유효한 종합 분석 실행';
COMMENT ON COLUMN casework.cases.deleted_at IS '사용자가 삭제를 요청한 시각';
COMMENT ON COLUMN casework.cases.hard_delete_after IS '삭제 유예 후 물리 삭제가 가능한 시각';
COMMENT ON COLUMN casework.files.object_key IS '객체 저장소 키. 원본 파기 성공 후 반드시 NULL';
COMMENT ON COLUMN casework.files.sha256 IS '파일 중복·무결성 검사용 SHA-256';
COMMENT ON COLUMN casework.files.current_ocr_revision_id IS '사용자가 확정한 현재 OCR 수정본';
COMMENT ON COLUMN casework.files.storage_expires_at IS '임시 원본 파일 보관 만료 시각';
COMMENT ON COLUMN casework.files.purge_status IS '원본 객체 파기 작업의 현재 상태';
COMMENT ON COLUMN casework.file_extractions.idempotency_key IS '외부 OCR 중복 실행을 차단하는 요청 키';
COMMENT ON COLUMN casework.file_extractions.raw_text IS 'OCR 공급자가 반환한 기계 추출 원문';
COMMENT ON COLUMN casework.ocr_text_revisions.corrected_text IS '사용자가 검토·수정한 영속 보관 텍스트';
COMMENT ON COLUMN casework.ocr_text_revisions.is_current IS '같은 extraction에서 현재 선택된 revision 여부';
COMMENT ON COLUMN casework.pii_findings.detected_text_hash IS '발견 문자열 원문 대신 저장하는 비교용 해시';
COMMENT ON COLUMN casework.fact_conflicts.resolved_value IS '사용자가 선택하거나 직접 입력한 최종 사실 값';
COMMENT ON COLUMN casework.evidence_items.status IS 'secured, needs_more, caution 중 하나인 증거 확보 상태';
COMMENT ON COLUMN casework.case_questions.reason IS '해당 확인 질문이 필요한 이유';

COMMENT ON COLUMN knowledge.legal_documents.effective_from IS '법률 문서 버전의 효력 시작일';
COMMENT ON COLUMN knowledge.legal_documents.is_current IS '현재 기준 검색에 기본 포함할 버전 여부';
COMMENT ON COLUMN knowledge.chunk_embeddings.embedding IS 'cosine 검색에 사용하는 pgvector 값';
COMMENT ON COLUMN knowledge.chunk_embeddings.content_hash IS '청크 변경과 embedding 재생성 판단용 hash';

COMMENT ON COLUMN aiops.model_runs.input_payload_enc IS '재현·장애 분석용 암호화 요청 payload. 만료 배치에서 NULL 처리';
COMMENT ON COLUMN aiops.model_runs.output_payload_enc IS '재현·장애 분석용 암호화 응답 payload. 만료 배치에서 NULL 처리';
COMMENT ON COLUMN aiops.model_runs.payload_expires_at IS '요청·응답 payload 제거 예정 시각';
COMMENT ON COLUMN aiops.analysis_runs.input_fingerprint IS '분석 입력 전체를 대표하는 재현성 hash';
COMMENT ON COLUMN aiops.analysis_runs.stale_at IS '사건 입력 변경으로 분석이 더 이상 유효하지 않게 된 시각';
COMMENT ON COLUMN aiops.retrieval_results.is_selected IS '최종 분석 생성에 실제 사용할 검색 결과로 선택됐는지 여부';
COMMENT ON COLUMN aiops.analysis_findings.confidence IS '0~1 범위의 모델 판단 신뢰도';
COMMENT ON COLUMN aiops.analysis_citations.quoted_text IS '사용자에게 표시할 최소 범위의 법률 근거 인용문';

COMMENT ON COLUMN workflow.generated_documents.content_enc IS '사용자가 최종 검토할 생성 문서 본문 암호문';
COMMENT ON COLUMN workflow.generated_documents.analysis_run_id IS '문서 생성에 사용한 succeeded·non-stale 분석';
COMMENT ON COLUMN audit.audit_logs.before_data IS '변경 전 최소 필요 필드. 민감 원문 저장 금지';
COMMENT ON COLUMN audit.audit_logs.after_data IS '변경 후 최소 필요 필드. 민감 원문 저장 금지';
COMMENT ON COLUMN ops.outbox_events.idempotency_key IS 'worker 재시도 시 외부 side effect 중복을 막는 키';
COMMENT ON COLUMN ops.outbox_events.lease_expires_at IS 'PROCESSING worker 장애 후 다른 worker가 회수 가능한 시각';
COMMENT ON COLUMN ops.runtime_settings.value_json IS '배포 없이 조정 가능한 정책 값. 변경은 감사 대상';
