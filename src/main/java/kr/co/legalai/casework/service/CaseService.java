package kr.co.legalai.casework.service;

import kr.co.legalai.casework.dto.request.CreateCaseRequest;
import kr.co.legalai.casework.dto.request.UpdateCaseRequest;
import kr.co.legalai.casework.dto.response.CaseResponse;

import java.util.UUID;

public interface CaseService {
    CaseResponse createCase(CreateCaseRequest request);

    CaseResponse getCase(UUID caseId);

    CaseResponse updateCase(UUID caseId, UpdateCaseRequest request);
}
