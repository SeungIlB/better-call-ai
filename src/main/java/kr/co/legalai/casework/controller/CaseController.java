package kr.co.legalai.casework.controller;

import jakarta.validation.Valid;
import kr.co.legalai.casework.dto.request.CreateCaseRequest;
import kr.co.legalai.casework.dto.request.UpdateCaseRequest;
import kr.co.legalai.casework.dto.response.CaseResponse;
import kr.co.legalai.casework.service.CaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * 사건 리소스의 생성, 단건 조회, 부분 수정을 제공하는 REST 컨트롤러.
 */
@RestController
@RequestMapping("/api/v1/cases")
public class CaseController {
    private final CaseService caseService;

    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    @PostMapping
    public ResponseEntity<CaseResponse> createCase(@Valid @RequestBody CreateCaseRequest request) {
        CaseResponse response = caseService.createCase(request);
        return ResponseEntity
                .created(URI.create("/api/v1/cases/" + response.id()))
                .body(response);
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<CaseResponse> getCase(@PathVariable UUID caseId) {
        return ResponseEntity.ok(caseService.getCase(caseId));
    }

    @PatchMapping("/{caseId}")
    public ResponseEntity<CaseResponse> updateCase(
            @PathVariable UUID caseId,
            @Valid @RequestBody UpdateCaseRequest request
    ) {
        return ResponseEntity.ok(caseService.updateCase(caseId, request));
    }
}
