package kr.co.legalai.file.controller;

import kr.co.legalai.common.response.ApiResponse;
import kr.co.legalai.file.dto.response.EvidenceContextResponse;
import kr.co.legalai.file.service.ConfirmedEvidenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/cases/{caseId}/confirmed-evidence")
public class ConfirmedEvidenceController {
    private final ConfirmedEvidenceService service;

    @GetMapping
    public ApiResponse<EvidenceContextResponse> get(@PathVariable UUID caseId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Integer expectedCaseVersion) {
        return ApiResponse.success(service.get(caseId, page, pageSize, expectedCaseVersion));
    }
}
