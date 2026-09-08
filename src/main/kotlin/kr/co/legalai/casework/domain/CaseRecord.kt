package kr.co.legalai.casework.domain

import java.time.Instant
import java.util.UUID

data class CaseRecord(
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
