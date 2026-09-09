package kr.co.legalai.common.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TraceIdFilterTest {
    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void keepsSafeClientTraceId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "client-trace_123");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("client-trace_123", response.getHeader("X-Trace-Id"));
    }

    @Test
    void replacesUnsafeClientTraceId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "bad trace id");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertNotEquals("bad trace id", response.getHeader("X-Trace-Id"));
    }
}
