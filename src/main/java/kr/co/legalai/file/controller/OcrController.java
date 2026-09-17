package kr.co.legalai.file.controller;

import jakarta.validation.Valid;
import kr.co.legalai.common.response.ApiResponse;
import kr.co.legalai.common.response.PageResponse;
import kr.co.legalai.file.dto.request.*;
import kr.co.legalai.file.dto.response.*;
import kr.co.legalai.file.service.OcrService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/cases/{caseId}/files/{fileId}")
public class OcrController {
    private final OcrService service;

    @PostMapping("/ocr")
    public ApiResponse<OcrResponse> extract(@PathVariable UUID caseId, @PathVariable UUID fileId,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody StartOcrRequest request) {
        return ApiResponse.success(service.extract(caseId, fileId, key, request));
    }

    @GetMapping("/ocr")
    public ApiResponse<OcrResponse> get(@PathVariable UUID caseId, @PathVariable UUID fileId) {
        return ApiResponse.success(service.get(caseId, fileId));
    }

    @PostMapping("/ocr-revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OcrRevisionResponse> save(@PathVariable UUID caseId, @PathVariable UUID fileId,
            @Valid @RequestBody SaveOcrRevisionRequest request) {
        return ApiResponse.success(service.save(caseId, fileId, request));
    }

    @GetMapping("/ocr-revisions")
    public ApiResponse<PageResponse<OcrRevisionResponse>> history(@PathVariable UUID caseId, @PathVariable UUID fileId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(service.history(caseId, fileId, page, pageSize));
    }

    @PostMapping("/confirm")
    public ApiResponse<OcrRevisionResponse> confirm(@PathVariable UUID caseId, @PathVariable UUID fileId,
            @Valid @RequestBody ConfirmOcrRequest request) {
        return ApiResponse.success(service.confirm(caseId, fileId, request));
    }
}
