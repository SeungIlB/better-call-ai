package kr.co.legalai.casework.application

import kr.co.legalai.casework.api.CaseResponse
import kr.co.legalai.casework.api.CreateCaseRequest
import kr.co.legalai.casework.api.UpdateStatementRequest
import kr.co.legalai.casework.domain.CaseRecord
import kr.co.legalai.casework.persistence.CaseRepository
import kr.co.legalai.common.persistence.UserScopedTransaction
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class CaseService(
    private val tx: UserScopedTransaction,
    private val cases: CaseRepository,
) {
    fun create(request: CreateCaseRequest): CaseResponse = tx.execute { userId ->
        val caseId = UUID.randomUUID()
        cases.insert(caseId, userId, request)
        cases.enqueue(
            eventId = UUID.randomUUID(),
            eventType = "CASE_CREATED",
            caseId = caseId,
            idempotencyKey = "case-created:$caseId",
            version = 1,
        )
        cases.require(caseId).toResponse()
    }

    fun get(caseId: UUID): CaseResponse = tx.execute {
        cases.require(caseId).toResponse()
    }

    fun updateStatement(caseId: UUID, request: UpdateStatementRequest): CaseResponse = tx.execute {
        val updated = cases.updateStatement(caseId, request.statement, request.expectedVersion)
        if (updated == 0) {
            if (cases.find(caseId) == null) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "사건을 찾을 수 없습니다.")
            }
            throw ResponseStatusException(HttpStatus.CONFLICT, "사건이 다른 요청에 의해 변경되었습니다.")
        }
        val nextVersion = request.expectedVersion + 1
        cases.markAnalysisStale(caseId)
        cases.enqueue(
            eventId = UUID.randomUUID(),
            eventType = "CASE_INPUT_CHANGED",
            caseId = caseId,
            idempotencyKey = "case-input-changed:$caseId:$nextVersion",
            version = nextVersion,
        )
        cases.require(caseId).toResponse()
    }

    private fun CaseRepository.require(id: UUID): CaseRecord =
        find(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "사건을 찾을 수 없습니다.")

    private fun CaseRecord.toResponse() = CaseResponse(
        id = id,
        title = title,
        status = status,
        userPartyRole = userPartyRole,
        userGoal = userGoal,
        originalStatement = originalStatement,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
