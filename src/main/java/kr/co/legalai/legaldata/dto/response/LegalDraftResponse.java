package kr.co.legalai.legaldata.dto.response;

import lombok.Builder;
import java.util.List;
import java.util.UUID;

@Builder
public record LegalDraftResponse(UUID caseId, int caseVersion, EvidenceExcerptResponse evidence, String status,
        String summary, List<GroundedFindingResponse> findings, List<String> questions, List<String> actionDraft,
        List<String> checklist, List<String> conflicts, String notice) {
    public LegalDraftResponse {
        findings = findings == null ? List.of() : List.copyOf(findings);
        questions = questions == null ? List.of() : List.copyOf(questions);
        actionDraft = actionDraft == null ? List.of() : List.copyOf(actionDraft);
        checklist = checklist == null ? List.of() : List.copyOf(checklist);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }
}
