package kr.co.legalai.common.security

import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AuthenticatedUser {
    fun id(): UUID {
        val subject = SecurityContextHolder.getContext().authentication?.name
            ?: throw AccessDeniedException("Authentication is required")
        return runCatching { UUID.fromString(subject) }.getOrElse {
            throw AccessDeniedException("JWT subject must be a UUID")
        }
    }
}
