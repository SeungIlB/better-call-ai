package kr.co.legalai.casework.api

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateCaseRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val title: String,
    @field:Size(max = 40)
    val userPartyRole: String? = null,
    @field:Size(max = 2_000)
    val userGoal: String? = null,
    @field:Size(max = 50_000)
    val originalStatement: String? = null,
)

data class UpdateStatementRequest(
    @field:NotBlank
    @field:Size(max = 50_000)
    val statement: String,
    @field:Positive
    val expectedVersion: Int,
)

data class CaseResponse(
    val id: UUID,
    val title: String,
    val status: String,
    val userPartyRole: String?,
    val userGoal: String?,
    val originalStatement: String?,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)
