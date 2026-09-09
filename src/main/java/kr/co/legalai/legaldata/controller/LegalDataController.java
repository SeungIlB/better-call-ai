package kr.co.legalai.legaldata.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.legalai.legaldata.dto.response.LegalDocumentResponse;
import kr.co.legalai.legaldata.dto.response.LegalSearchResponse;
import kr.co.legalai.legaldata.entity.LegalDocumentType;
import kr.co.legalai.legaldata.service.LegalDataService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공식 법령·판례의 검색 및 원문 조회를 제공한다.
 */
@Validated
@RestController
@RequestMapping("/api/v1/legal-data")
public class LegalDataController {
    private final LegalDataService service;

    public LegalDataController(LegalDataService service) {
        this.service = service;
    }

    @GetMapping("/{type}")
    public LegalSearchResponse search(
            @PathVariable String type,
            @RequestParam @NotBlank @Size(max = 200) String query,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return service.search(parseType(type), query, page, pageSize);
    }

    @GetMapping("/{type}/{externalId}")
    public LegalDocumentResponse getDocument(
            @PathVariable String type,
            @PathVariable @NotBlank @Size(max = 255) String externalId
    ) {
        return service.getDocument(parseType(type), externalId);
    }

    private LegalDocumentType parseType(String type) {
        return LegalDocumentType.valueOf(type.toUpperCase());
    }
}
