ALTER TABLE casework.file_extractions
  ADD CONSTRAINT fk_extraction_model_run
  FOREIGN KEY (model_run_id) REFERENCES aiops.model_runs(id) ON DELETE SET NULL;

ALTER TABLE casework.case_questions
  ADD CONSTRAINT fk_question_model_run
  FOREIGN KEY (model_run_id) REFERENCES aiops.model_runs(id) ON DELETE SET NULL;

ALTER TABLE casework.ocr_text_revisions
  ADD CONSTRAINT fk_revision_file_extraction
  FOREIGN KEY (file_id, extraction_id)
  REFERENCES casework.file_extractions(file_id, id)
  DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE casework.extracted_fields
  ADD CONSTRAINT fk_field_revision_extraction
  FOREIGN KEY (extraction_id, ocr_revision_id)
  REFERENCES casework.ocr_text_revisions(extraction_id, id)
  DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE casework.files
  ADD CONSTRAINT fk_file_current_extraction
  FOREIGN KEY (id, current_extraction_id)
  REFERENCES casework.file_extractions(file_id, id)
  DEFERRABLE INITIALLY DEFERRED,
  ADD CONSTRAINT fk_file_current_revision
  FOREIGN KEY (id, current_ocr_revision_id)
  REFERENCES casework.ocr_text_revisions(file_id, id)
  DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE casework.cases
  ADD CONSTRAINT fk_case_current_analysis
  FOREIGN KEY (id, current_analysis_run_id)
  REFERENCES aiops.analysis_runs(case_id, id)
  DEFERRABLE INITIALLY DEFERRED;

CREATE OR REPLACE FUNCTION casework.assert_file_owner()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM casework.cases c
    WHERE c.id = NEW.case_id AND c.owner_user_id = NEW.uploaded_by
  ) THEN RAISE EXCEPTION 'uploaded_by must own the case';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_file_owner
BEFORE INSERT OR UPDATE OF case_id, uploaded_by ON casework.files
FOR EACH ROW EXECUTE FUNCTION casework.assert_file_owner();

CREATE OR REPLACE FUNCTION casework.assert_evidence_claim_case()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM casework.evidence_items e
    JOIN casework.case_claims c ON c.id = NEW.claim_id
    WHERE e.id = NEW.evidence_id AND e.case_id = c.case_id
  ) THEN RAISE EXCEPTION 'evidence and claim must belong to the same case';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_evidence_claim_case
BEFORE INSERT OR UPDATE ON casework.evidence_claim_links
FOR EACH ROW EXECUTE FUNCTION casework.assert_evidence_claim_case();

CREATE OR REPLACE FUNCTION workflow.assert_document_analysis_current()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.analysis_run_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM aiops.analysis_runs a
    WHERE a.id = NEW.analysis_run_id
      AND a.case_id = NEW.case_id
      AND a.status = 'succeeded'
      AND a.stale_at IS NULL
  ) THEN RAISE EXCEPTION 'document requires current succeeded analysis';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_document_current_analysis
BEFORE INSERT ON workflow.generated_documents
FOR EACH ROW EXECUTE FUNCTION workflow.assert_document_analysis_current();

CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END $$;

CREATE TRIGGER trg_cases_updated BEFORE UPDATE ON casework.cases
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE TRIGGER trg_parties_updated BEFORE UPDATE ON casework.case_parties
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE TRIGGER trg_claims_updated BEFORE UPDATE ON casework.case_claims
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE TRIGGER trg_documents_updated BEFORE UPDATE ON workflow.generated_documents
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE INDEX ix_cases_owner_active
  ON casework.cases(owner_user_id, status, updated_at DESC)
  WHERE deleted_at IS NULL;
CREATE INDEX ix_cases_hard_delete
  ON casework.cases(hard_delete_after) WHERE deleted_at IS NOT NULL;
CREATE INDEX ix_cases_retention
  ON casework.cases(retention_expires_at) WHERE deleted_at IS NULL;
CREATE INDEX ix_files_case_status
  ON casework.files(case_id, lifecycle_status) WHERE removed_at IS NULL;
CREATE INDEX ix_files_purge_retry
  ON casework.files(purge_status, purge_next_retry_at);
CREATE INDEX ix_files_expiry
  ON casework.files(storage_expires_at) WHERE purged_at IS NULL;
CREATE INDEX ix_fields_case_group
  ON casework.extracted_fields(case_id, field_group, ocr_revision_id);
CREATE INDEX ix_pii_pending
  ON casework.pii_findings(case_id, mask_status);
CREATE INDEX ix_conflicts_open
  ON casework.fact_conflicts(case_id, status, updated_at DESC);
CREATE INDEX ix_claims_case
  ON casework.case_claims(case_id, status);
CREATE INDEX ix_questions_next
  ON casework.case_questions(case_id, status, priority DESC, sequence_no);
CREATE INDEX ix_legal_current
  ON knowledge.legal_documents(document_type, is_current);
CREATE INDEX ix_legal_chunks_fts
  ON knowledge.legal_chunks USING gin(search_vector);
CREATE INDEX ix_embeddings_hnsw
  ON knowledge.chunk_embeddings USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ix_analysis_latest
  ON aiops.analysis_runs(case_id, stale_at, completed_at DESC);
CREATE INDEX ix_model_payload_expiry
  ON aiops.model_runs(payload_expires_at);
CREATE INDEX ix_documents_case
  ON workflow.generated_documents(case_id, updated_at DESC);
CREATE INDEX ix_outbox_ready
  ON ops.outbox_events(status, next_retry_at, created_at)
  WHERE status IN ('PENDING','RETRY_WAIT');
CREATE INDEX ix_outbox_lease
  ON ops.outbox_events(lease_expires_at) WHERE status = 'PROCESSING';
CREATE INDEX ix_audit_case
  ON audit.audit_logs(case_id, created_at DESC);

