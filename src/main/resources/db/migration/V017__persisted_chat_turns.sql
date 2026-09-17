-- 한 사건의 채팅은 사용자 질문과 확정된 답변을 한 턴으로 관리한다.
CREATE TABLE casework.chat_turns (
    case_id uuid NOT NULL REFERENCES casework.cases(id) ON DELETE CASCADE,
    id uuid NOT NULL,
    owner_user_id uuid NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
    turn_no bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    question text NOT NULL CHECK (length(btrim(question)) BETWEEN 1 AND 4000),
    answer text CHECK (length(btrim(answer)) BETWEEN 1 AND 16000),
    status varchar(20) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    attempt integer NOT NULL DEFAULT 1 CHECK (attempt > 0),
    started_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    answered_at timestamptz,
    error_code varchar(80),
    model_name varchar(100),
    provider_response_id varchar(200),
    input_tokens integer CHECK (input_tokens >= 0),
    output_tokens integer CHECK (output_tokens >= 0),
    PRIMARY KEY (case_id, id),
    CHECK ((status = 'COMPLETED') = (answer IS NOT NULL AND answered_at IS NOT NULL)),
    CHECK (status = 'COMPLETED' OR (answer IS NULL AND answered_at IS NULL)),
    CHECK ((status = 'FAILED') = (error_code IS NOT NULL))
);
CREATE UNIQUE INDEX uq_chat_active_case ON casework.chat_turns(case_id) WHERE status = 'RUNNING';
CREATE INDEX ix_chat_active_owner ON casework.chat_turns(owner_user_id, started_at) WHERE status = 'RUNNING';
CREATE INDEX ix_chat_case_history ON casework.chat_turns(case_id, turn_no DESC);

ALTER TABLE casework.chat_turns ENABLE ROW LEVEL SECURITY;
ALTER TABLE casework.chat_turns FORCE ROW LEVEL SECURITY;
CREATE POLICY chat_owner ON casework.chat_turns
USING (owner_user_id = identity.current_user_id() AND casework.can_access_case(case_id))
WITH CHECK (owner_user_id = identity.current_user_id() AND casework.can_access_case(case_id));
GRANT SELECT, INSERT, UPDATE ON casework.chat_turns TO legal_ai_app;
GRANT USAGE ON SEQUENCE casework.chat_turns_turn_no_seq TO legal_ai_app;

COMMENT ON TABLE casework.chat_turns IS '사건별 사용자 질문과 저장 완료된 AI 답변. 내부 실행 상태는 API 응답에서 제외';
COMMENT ON COLUMN casework.chat_turns.case_id IS '소유권 검사 대상 사건. 사건 영구 삭제 시 함께 삭제';
COMMENT ON COLUMN casework.chat_turns.id IS '클라이언트가 생성한 UUID 멱등 키. 같은 사건 내 재전송은 같은 턴을 참조';
COMMENT ON COLUMN casework.chat_turns.owner_user_id IS '인증 JWT sub에서만 결정하는 사용자 ID';
COMMENT ON COLUMN casework.chat_turns.turn_no IS '동일 시각에도 순서가 유지되는 서버 발급 정렬 번호';
COMMENT ON COLUMN casework.chat_turns.question IS '사용자 질문 원문. 최대 4000자. 로그 출력 금지';
COMMENT ON COLUMN casework.chat_turns.answer IS '완전히 생성·검증된 답변만 저장. 부분 답변과 장애 안내문은 저장하지 않음';
COMMENT ON COLUMN casework.chat_turns.status IS '내부 상태 RUNNING/COMPLETED/FAILED. 화면용 메시지 아님';
COMMENT ON COLUMN casework.chat_turns.attempt IS '명시적 재시도마다 증가하는 실행 세대. 이전 실행의 늦은 저장 차단';
COMMENT ON COLUMN casework.chat_turns.started_at IS '현재 시도 시작 UTC 시각. 3분 지난 실행은 다음 접근에서 실패 처리';
COMMENT ON COLUMN casework.chat_turns.created_at IS '최초 사용자 질문 저장 UTC 시각';
COMMENT ON COLUMN casework.chat_turns.answered_at IS '완성 답변 저장 UTC 시각';
COMMENT ON COLUMN casework.chat_turns.error_code IS '실패의 내부 열거형 코드. 외부 API 원문 저장 금지';
COMMENT ON COLUMN casework.chat_turns.model_name IS '성공 답변을 생성한 모델명';
COMMENT ON COLUMN casework.chat_turns.provider_response_id IS '성공 응답의 외부 추적 ID. 재생성·과금 취소를 보장하는 키가 아님';
COMMENT ON COLUMN casework.chat_turns.input_tokens IS '성공한 최종 응답의 입력 토큰. 업로드 사용량과 별도이며 전체 청구 원장은 아님';
COMMENT ON COLUMN casework.chat_turns.output_tokens IS '성공한 최종 응답의 출력 토큰. 실패·재시도 과금은 공급자 사용량과 대조 필요';
