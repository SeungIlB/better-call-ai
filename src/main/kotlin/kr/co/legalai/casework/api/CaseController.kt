package kr.co.legalai.casework.api

import jakarta.validation.Valid
import kr.co.legalai.casework.application.CaseService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1/cases")
class CaseController(
    private val service: CaseService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateCaseRequest) = service.create(request)

    @GetMapping("/{caseId}")
    fun get(@PathVariable caseId: UUID) = service.get(caseId)

    @PutMapping("/{caseId}/statement")
    fun updateStatement(
        @PathVariable caseId: UUID,
        @Valid @RequestBody request: UpdateStatementRequest,
    ) = service.updateStatement(caseId, request)
}
