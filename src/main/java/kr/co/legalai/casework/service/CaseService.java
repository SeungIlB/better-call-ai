package kr.co.legalai.casework.service;

import kr.co.legalai.casework.dto.request.CreateCaseRequest;
import kr.co.legalai.casework.dto.request.UpdateCaseRequest;
import kr.co.legalai.casework.dto.response.CaseResponse;

import java.util.UUID;

/**
 * 사건 유스케이스의 애플리케이션 서비스 계약.
 */
public interface CaseService {
    CaseResponse createCase(CreateCaseRequest request);

    CaseResponse getCase(UUID caseId);

    CaseResponse updateCase(UUID caseId, UpdateCaseRequest request);
}
