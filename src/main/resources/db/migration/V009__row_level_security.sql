CREATE OR REPLACE FUNCTION identity.current_user_id()
RETURNS uuid LANGUAGE sql STABLE AS $$
  SELECT nullif(current_setting('app.user_id', true), '')::uuid
$$;

CREATE OR REPLACE FUNCTION casework.can_access_case(p_case_id uuid)
RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = casework, pg_catalog
AS $$
  SELECT EXISTS (
    SELECT 1 FROM casework.cases c
    WHERE c.id = p_case_id
      AND c.owner_user_id = identity.current_user_id()
      AND c.deleted_at IS NULL
  )
$$;

REVOKE ALL ON FUNCTION casework.can_access_case(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION casework.can_access_case(uuid) TO legal_ai_app;

ALTER TABLE identity.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.users FORCE ROW LEVEL SECURITY;
CREATE POLICY users_self ON identity.users
USING (id = identity.current_user_id())
WITH CHECK (id = identity.current_user_id());

ALTER TABLE identity.auth_identities ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.auth_identities FORCE ROW LEVEL SECURITY;
CREATE POLICY auth_identities_self ON identity.auth_identities
USING (user_id = identity.current_user_id())
WITH CHECK (user_id = identity.current_user_id());

ALTER TABLE identity.user_consents ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.user_consents FORCE ROW LEVEL SECURITY;
CREATE POLICY user_consents_self ON identity.user_consents
USING (user_id = identity.current_user_id())
WITH CHECK (user_id = identity.current_user_id());

ALTER TABLE casework.cases ENABLE ROW LEVEL SECURITY;
ALTER TABLE casework.cases FORCE ROW LEVEL SECURITY;
CREATE POLICY cases_owner ON casework.cases
USING (owner_user_id = identity.current_user_id() AND deleted_at IS NULL)
WITH CHECK (owner_user_id = identity.current_user_id());

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'casework.case_parties','casework.case_statements','casework.case_events',
    'casework.files','casework.extracted_fields','casework.pii_findings',
    'casework.fact_conflicts','casework.case_claims','casework.evidence_items',
    'casework.case_questions','casework.case_answers',
    'aiops.model_runs','aiops.retrieval_runs','aiops.analysis_runs',
    'workflow.action_plans','workflow.generated_documents','audit.audit_logs'
  ] LOOP
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY owner_case_access ON %s USING (casework.can_access_case(case_id)) WITH CHECK (casework.can_access_case(case_id))',
      t
    );
  END LOOP;
END $$;

DROP POLICY owner_case_access ON audit.audit_logs;
CREATE POLICY audit_self ON audit.audit_logs
USING (
  actor_user_id = identity.current_user_id()
  AND (case_id IS NULL OR casework.can_access_case(case_id))
)
WITH CHECK (
  actor_user_id = identity.current_user_id()
  AND (case_id IS NULL OR casework.can_access_case(case_id))
);

ALTER TABLE casework.event_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE casework.event_sources FORCE ROW LEVEL SECURITY;
CREATE POLICY event_sources_owner ON casework.event_sources
USING (EXISTS (
  SELECT 1 FROM casework.case_events e
  WHERE e.id = event_id AND casework.can_access_case(e.case_id)
))
WITH CHECK (EXISTS (
  SELECT 1 FROM casework.case_events e
  WHERE e.id = event_id AND casework.can_access_case(e.case_id)
));

ALTER TABLE casework.file_extractions ENABLE ROW LEVEL SECURITY;
ALTER TABLE casework.file_extractions FORCE ROW LEVEL SECURITY;
CREATE POLICY extractions_owner ON casework.file_extractions
USING (EXISTS (
  SELECT 1 FROM casework.files f
  WHERE f.id = file_id AND casework.can_access_case(f.case_id)
))
WITH CHECK (EXISTS (
  SELECT 1 FROM casework.files f
  WHERE f.id = file_id AND casework.can_access_case(f.case_id)
));

ALTER TABLE casework.ocr_text_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE casework.ocr_text_revisions FORCE ROW LEVEL SECURITY;
CREATE POLICY revisions_owner ON casework.ocr_text_revisions
USING (EXISTS (
  SELECT 1 FROM casework.files f
  WHERE f.id = file_id AND casework.can_access_case(f.case_id)
))
WITH CHECK (EXISTS (
  SELECT 1 FROM casework.files f
  WHERE f.id = file_id AND casework.can_access_case(f.case_id)
));

ALTER TABLE casework.evidence_claim_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE casework.evidence_claim_links FORCE ROW LEVEL SECURITY;
CREATE POLICY evidence_claim_owner ON casework.evidence_claim_links
USING (EXISTS (
  SELECT 1 FROM casework.evidence_items e
  WHERE e.id = evidence_id AND casework.can_access_case(e.case_id)
))
WITH CHECK (EXISTS (
  SELECT 1 FROM casework.evidence_items e
  WHERE e.id = evidence_id AND casework.can_access_case(e.case_id)
));

ALTER TABLE aiops.analysis_run_inputs ENABLE ROW LEVEL SECURITY;
ALTER TABLE aiops.analysis_run_inputs FORCE ROW LEVEL SECURITY;
CREATE POLICY analysis_inputs_owner ON aiops.analysis_run_inputs
USING (EXISTS (
  SELECT 1 FROM aiops.analysis_runs a
  WHERE a.id = analysis_run_id AND casework.can_access_case(a.case_id)
))
WITH CHECK (EXISTS (
  SELECT 1 FROM aiops.analysis_runs a
  WHERE a.id = analysis_run_id AND casework.can_access_case(a.case_id)
));

ALTER TABLE aiops.retrieval_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE aiops.retrieval_results FORCE ROW LEVEL SECURITY;
CREATE POLICY retrieval_results_owner ON aiops.retrieval_results
USING (EXISTS (
  SELECT 1 FROM aiops.retrieval_runs r
  WHERE r.id = retrieval_run_id AND casework.can_access_case(r.case_id)
))
WITH CHECK (EXISTS (
  SELECT 1 FROM aiops.retrieval_runs r
  WHERE r.id = retrieval_run_id AND casework.can_access_case(r.case_id)
));

ALTER TABLE aiops.analysis_findings ENABLE ROW LEVEL SECURITY;
ALTER TABLE aiops.analysis_findings FORCE ROW LEVEL SECURITY;
CREATE POLICY findings_owner ON aiops.analysis_findings
USING (EXISTS (
  SELECT 1 FROM aiops.analysis_runs a
  WHERE a.id = analysis_run_id AND casework.can_access_case(a.case_id)
))
WITH CHECK (EXISTS (
  SELECT 1 FROM aiops.analysis_runs a
  WHERE a.id = analysis_run_id AND casework.can_access_case(a.case_id)
));

ALTER TABLE aiops.analysis_finding_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE aiops.analysis_finding_sources FORCE ROW LEVEL SECURITY;
CREATE POLICY finding_sources_owner ON aiops.analysis_finding_sources
USING (EXISTS (
  SELECT 1 FROM aiops.analysis_findings f
  JOIN aiops.analysis_runs a ON a.id = f.analysis_run_id
  WHERE f.id = finding_id AND casework.can_access_case(a.case_id)
))
WITH CHECK (EXISTS (
  SELECT 1 FROM aiops.analysis_findings f
  JOIN aiops.analysis_runs a ON a.id = f.analysis_run_id
  WHERE f.id = finding_id AND casework.can_access_case(a.case_id)
));

ALTER TABLE aiops.analysis_citations ENABLE ROW LEVEL SECURITY;
ALTER TABLE aiops.analysis_citations FORCE ROW LEVEL SECURITY;
CREATE POLICY citations_owner ON aiops.analysis_citations
USING (EXISTS (
  SELECT 1 FROM aiops.analysis_findings f
  JOIN aiops.analysis_runs a ON a.id = f.analysis_run_id
  WHERE f.id = finding_id AND casework.can_access_case(a.case_id)
))
WITH CHECK (EXISTS (
  SELECT 1 FROM aiops.analysis_findings f
  JOIN aiops.analysis_runs a ON a.id = f.analysis_run_id
  WHERE f.id = finding_id AND casework.can_access_case(a.case_id)
));

ALTER TABLE workflow.action_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.action_items FORCE ROW LEVEL SECURITY;
CREATE POLICY action_items_owner ON workflow.action_items
USING (EXISTS (
  SELECT 1 FROM workflow.action_plans p
  WHERE p.id = action_plan_id AND casework.can_access_case(p.case_id)
))
WITH CHECK (EXISTS (
  SELECT 1 FROM workflow.action_plans p
  WHERE p.id = action_plan_id AND casework.can_access_case(p.case_id)
));

ALTER TABLE workflow.document_participant_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.document_participant_snapshots FORCE ROW LEVEL SECURITY;
CREATE POLICY document_participants_owner ON workflow.document_participant_snapshots
USING (EXISTS (
  SELECT 1 FROM workflow.generated_documents d
  WHERE d.id = generated_document_id AND casework.can_access_case(d.case_id)
))
WITH CHECK (EXISTS (
  SELECT 1 FROM workflow.generated_documents d
  WHERE d.id = generated_document_id AND casework.can_access_case(d.case_id)
));

ALTER TABLE workflow.generated_document_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow.generated_document_sources FORCE ROW LEVEL SECURITY;
CREATE POLICY document_sources_owner ON workflow.generated_document_sources
USING (EXISTS (
  SELECT 1 FROM workflow.generated_documents d
  WHERE d.id = generated_document_id AND casework.can_access_case(d.case_id)
))
WITH CHECK (EXISTS (
  SELECT 1 FROM workflow.generated_documents d
  WHERE d.id = generated_document_id AND casework.can_access_case(d.case_id)
));

