package kr.co.legalai.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityErrorHandlerTest {
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void unauthorizedResponseDoesNotExposeAuthenticationDetails() throws Exception {
        MDC.put("traceId", "trace-test-1234");
        var response = new MockHttpServletResponse();
        var handler = new ApiAuthenticationEntryPoint(new ObjectMapper());

        handler.commence(
                new MockHttpServletRequest(),
                response,
                new AuthenticationCredentialsNotFoundException("raw token detail")
        );

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTH_001"));
        assertTrue(response.getContentAsString().contains("trace-test-1234"));
        assertFalse(response.getContentAsString().contains("raw token detail"));
    }
}
