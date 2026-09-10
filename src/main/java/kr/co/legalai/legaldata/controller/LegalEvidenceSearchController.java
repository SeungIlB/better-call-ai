package kr.co.legalai.legaldata.controller;

import jakarta.validation.Valid;
import kr.co.legalai.common.response.ApiResponse;
import kr.co.legalai.common.response.PageResponse;
import kr.co.legalai.legaldata.dto.request.SearchLegalEvidenceRequest;
import kr.co.legalai.legaldata.dto.response.LegalEvidenceResponse;
import kr.co.legalai.legaldata.service.LegalEvidenceSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class LegalEvidenceSearchController {
    private final LegalEvidenceSearchService service;

    @PostMapping("/api/v1/legal-evidence/search")
    public ApiResponse<PageResponse<LegalEvidenceResponse>> search(@Valid @RequestBody SearchLegalEvidenceRequest request) {
        return ApiResponse.success(service.search(request.query()));
    }
}
