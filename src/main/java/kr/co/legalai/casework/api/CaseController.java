package kr.co.legalai.casework.api;

import jakarta.validation.Valid;
import kr.co.legalai.casework.application.CaseService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/cases")
public class CaseController {
    private final CaseService service;

    public CaseController(CaseService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CaseResponse create(@Valid @RequestBody CreateCaseRequest request) {
        return service.create(request);
    }

    @GetMapping("/{caseId}")
    public CaseResponse get(@PathVariable UUID caseId) {
        return service.get(caseId);
    }

    @PutMapping("/{caseId}/statement")
    public CaseResponse updateStatement(
            @PathVariable UUID caseId,
            @Valid @RequestBody UpdateStatementRequest request
    ) {
        return service.updateStatement(caseId, request);
    }
}
