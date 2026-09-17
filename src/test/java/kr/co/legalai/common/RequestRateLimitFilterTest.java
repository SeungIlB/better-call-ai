package kr.co.legalai.common;

import kr.co.legalai.common.filter.RequestRateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class RequestRateLimitFilterTest {
    @Test
    void limitsApiRequestsPerRemoteAndPathWindow() throws Exception {
        var filter = new RequestRateLimitFilter(new ObjectMapper(), 1);
        var first = request();
        filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());
        var second = new MockHttpServletResponse();
        filter.doFilter(request(), second, new MockFilterChain());
        assertEquals(429, second.getStatus());
        assertTrue(second.getContentAsString().contains("COMMON_003"));
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/v1/cases");
        request.setRemoteAddr("192.0.2.10");
        return request;
    }
}
