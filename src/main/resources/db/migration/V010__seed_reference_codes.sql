CREATE TABLE ops.runtime_settings (
    setting_key varchar(100) PRIMARY KEY,
    value_json jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO ops.runtime_settings(setting_key, value_json) VALUES
('upload.allowed_types', '["image/jpeg","image/png","image/webp","application/pdf"]'),
('upload.max_file_bytes', '20971520'),
('upload.max_pdf_pages', '30'),
('upload.max_files_per_case', '10'),
('upload.max_case_bytes', '104857600'),
('original.max_retention_hours', '24'),
('case.delete_grace_days', '7'),
('case.inactive_retention_days', '365'),
('case.retention_notice_days', '30'),
('model.payload_retention_days', '7'),
('audit.retention_days', '90'),
('daily.ocr_files', '30'),
('daily.analyses', '20'),
('daily.documents', '30'),
('responsibility.scale', '["VERY_FAVORABLE","FAVORABLE","UNCERTAIN","UNFAVORABLE","VERY_UNFAVORABLE"]'),
('default.document_type', '"message"');

GRANT USAGE ON SCHEMA identity TO legal_ai_auth;
GRANT SELECT, INSERT, UPDATE
ON ALL TABLES IN SCHEMA identity
TO legal_ai_auth;

GRANT USAGE ON SCHEMA identity, casework, knowledge, aiops, workflow, audit, ops
TO legal_ai_app;

GRANT SELECT, INSERT, UPDATE, DELETE
ON ALL TABLES IN SCHEMA identity, casework, aiops, workflow
TO legal_ai_app;

GRANT SELECT, INSERT
ON ALL TABLES IN SCHEMA audit
TO legal_ai_app;

GRANT SELECT
ON ALL TABLES IN SCHEMA knowledge
TO legal_ai_app;

GRANT SELECT ON ops.runtime_settings TO legal_ai_app;
GRANT INSERT ON ops.outbox_events TO legal_ai_app;

REVOKE UPDATE, DELETE ON ALL TABLES IN SCHEMA audit FROM legal_ai_app;
REVOKE INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA knowledge FROM legal_ai_app;
REVOKE SELECT, UPDATE, DELETE ON ops.outbox_events FROM legal_ai_app;
REVOKE INSERT, UPDATE, DELETE ON aiops.prompt_versions FROM legal_ai_app;
GRANT SELECT ON aiops.prompt_versions TO legal_ai_app;
