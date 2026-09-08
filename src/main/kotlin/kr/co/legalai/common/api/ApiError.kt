package kr.co.legalai.common.api

import java.time.Instant

data class ApiError(
    val code: String,
    val message: String,
    val traceId: String?,
    val occurredAt: Instant = Instant.now(),
)
