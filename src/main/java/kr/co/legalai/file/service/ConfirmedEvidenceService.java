package kr.co.legalai.file.service;

import kr.co.legalai.file.dto.response.EvidenceContextResponse;
import java.util.UUID;

public interface ConfirmedEvidenceService {
    EvidenceContextResponse get(UUID caseId, int page, int pageSize, Integer expectedCaseVersion);
}
