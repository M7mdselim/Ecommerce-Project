package com.microservice.pro.order_service.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * FeignJwtInterceptor propagates the incoming JWT token from the security context
 * to outgoing service-to-service calls made via Feign.
 */
@Component
public class FeignJwtInterceptor implements RequestInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(FeignJwtInterceptor.class);

    @Override
    public void apply(RequestTemplate template) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                // Securely log propagation without printing the token itself
                logger.info("Propagating JWT token to outgoing Feign request. Header length: {}", authHeader.length());
                template.header(HttpHeaders.AUTHORIZATION, authHeader);
            } else {
                logger.warn("Authorization header is missing or not a Bearer token in the current HTTP request.");
            }
        } else {
            logger.warn("No request context found. JWT token cannot be propagated.");
        }
    }
}
