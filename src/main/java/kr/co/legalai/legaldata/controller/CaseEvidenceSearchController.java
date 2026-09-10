package kr.co.legalai.legaldata.controller;

import jakarta.validation.Valid;
import kr.co.legalai.common.response.ApiResponse;
import kr.co.legalai.legaldata.dto.request.CaseEvidenceSearchRequest;
import kr.co.legalai.legaldata.dto.response.CaseEvidenceSearchResponse;
import kr.co.legalai.legaldata.service.CaseEvidenceSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class CaseEvidenceSearchController {
    private final CaseEvidenceSearchService service;

    @PostMapping("/api/v1/cases/{caseId}/files/{fileId}/legal-evidence/search")
    public ApiResponse<CaseEvidenceSearchResponse> search(@PathVariable UUID caseId, @PathVariable UUID fileId,
            @Valid @RequestBody CaseEvidenceSearchRequest request) {
        return ApiResponse.success(service.search(caseId, fileId, request));
    }
}
