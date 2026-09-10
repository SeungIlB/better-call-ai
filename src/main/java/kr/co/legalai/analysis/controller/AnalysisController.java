package kr.co.legalai.analysis.controller;

import jakarta.validation.Valid;
import kr.co.legalai.analysis.dto.request.AnalysisRequest;
import kr.co.legalai.analysis.dto.response.AnalysisResponse;
import kr.co.legalai.analysis.service.AnalysisService;
import kr.co.legalai.common.response.ApiResponse;
import kr.co.legalai.common.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/cases/{caseId}/analyses")
public class AnalysisController {
    private final AnalysisService service;

    @PostMapping
    public ResponseEntity<ApiResponse<AnalysisResponse>> create(@PathVariable UUID caseId,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody AnalysisRequest request) {
        var result = service.create(caseId, key, request);
        return ResponseEntity.status(result.status().equals("running") ? 202 : 200).body(ApiResponse.success(result));
    }
    @GetMapping("/{id}")
    public ApiResponse<AnalysisResponse> get(@PathVariable UUID caseId, @PathVariable UUID id) {
        return ApiResponse.success(service.get(caseId, id));
    }
    @GetMapping
    public ApiResponse<PageResponse<AnalysisResponse>> list(@PathVariable UUID caseId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.success(service.list(caseId, page, pageSize));
    }
}
