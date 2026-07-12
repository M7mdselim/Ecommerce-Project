package com.microservice.pro.order_service;

import com.microservice.pro.order_service.config.FeignJwtInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for FeignJwtInterceptor verifying that Authorization headers are propagated correctly.
 */
public class FeignJwtInterceptorTest {

    private final FeignJwtInterceptor interceptor = new FeignJwtInterceptor();
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void testApply_WithJwtToken() {
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer mock-token-12345");
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        Collection<String> authHeaders = template.headers().get(HttpHeaders.AUTHORIZATION);
        assertEquals(1, authHeaders.size());
        assertTrue(authHeaders.contains("Bearer mock-token-12345"));
    }

    @Test
    void testApply_WithoutJwtToken() {
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertNull(template.headers().get(HttpHeaders.AUTHORIZATION));
    }
}
