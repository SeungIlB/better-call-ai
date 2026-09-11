package kr.co.legalai.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.legalai.common.exception.ErrorCode;
import kr.co.legalai.common.exception.ErrorResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** 인스턴스 단위의 순간 요청 보호. 상품 사용량·일일 업로드 정책과 분리한다. */
@Component
public class RequestRateLimitFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    private final int limit;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RequestRateLimitFilter(ObjectMapper mapper,
            @Value("${server.protection.requests-per-minute:600}") int limit) {
        if (limit < 1 || limit > 100_000) throw new IllegalArgumentException("요청 제한 범위를 확인해 주세요.");
        this.mapper = mapper;
        this.limit = limit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getRemoteAddr() + "|" + request.getRequestURI();
        if (!allow(key)) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            mapper.writeValue(response.getOutputStream(), ErrorResponse.of(ErrorCode.REQUEST_RATE_LIMITED, MDC.get("traceId")));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean allow(String key) {
        long now = System.nanoTime();
        Window window = windows.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAt() >= Duration.ofMinutes(1).toNanos()) return new Window(now, 1);
            return new Window(current.startedAt(), current.count() + 1);
        });
        if (windows.size() > 10_000) windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= Duration.ofMinutes(1).toNanos());
        return window.count() <= limit;
    }

    private record Window(long startedAt, int count) { }
}
