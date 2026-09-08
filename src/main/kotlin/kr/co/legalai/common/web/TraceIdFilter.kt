package kr.co.legalai.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
class TraceIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val traceId = request.getHeader("X-Trace-Id")?.takeIf { it.length <= 100 } ?: UUID.randomUUID().toString()
        MDC.put("traceId", traceId)
        response.setHeader("X-Trace-Id", traceId)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove("traceId")
        }
    }
}
