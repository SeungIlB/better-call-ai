ALTER TABLE casework.file_extractions
    ADD COLUMN provider_response_id varchar(200),
    ADD COLUMN input_tokens integer CHECK (input_tokens >= 0),
    ADD COLUMN output_tokens integer CHECK (output_tokens >= 0);

COMMENT ON COLUMN casework.file_extractions.provider_response_id IS 'OpenAI OCR 응답 ID. 원본·API 키는 저장하지 않는다.';
COMMENT ON COLUMN casework.file_extractions.input_tokens IS 'OCR 호출 입력 토큰. 업로드 횟수·채팅 사용량과 별도 기록.';
COMMENT ON COLUMN casework.file_extractions.output_tokens IS 'OCR 호출 출력 토큰. 공급자가 제공하지 않으면 NULL.';
