package kr.co.legalai.legaldata.controller;

import jakarta.validation.Valid;
import kr.co.legalai.common.response.ApiResponse;
import kr.co.legalai.legaldata.dto.request.CaseEvidenceSearchRequest;
import kr.co.legalai.legaldata.dto.response.LegalDraftResponse;
import kr.co.legalai.legaldata.service.LegalDraftService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class LegalDraftController {
    private final LegalDraftService service;

    @PostMapping("/api/v1/cases/{caseId}/files/{fileId}/legal-draft")
    public ApiResponse<LegalDraftResponse> generate(@PathVariable UUID caseId, @PathVariable UUID fileId,
            @Valid @RequestBody CaseEvidenceSearchRequest request) {
        return ApiResponse.success(service.generate(caseId, fileId, request));
    }
}
