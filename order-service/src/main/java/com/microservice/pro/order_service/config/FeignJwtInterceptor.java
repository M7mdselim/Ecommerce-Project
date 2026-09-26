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
 * FeignJwtInterceptor propagates security context headers from the incoming HTTP request
 * to outgoing Feign inter-service calls.
 *
 * <p><strong>Session 20 update — Client Credentials: Order → Inventory verified</strong>
 * <p>The API Gateway validates incoming JWTs and injects two trusted headers:
 * <ul>
 *   <li>{@code X-User-Id}   — the JWT {@code sub} claim (user identifier)</li>
 *   <li>{@code X-User-Role} — comma-separated roles from the JWT {@code roles} claim</li>
 * </ul>
 * <p>This interceptor forwards both headers alongside the original
 * {@code Authorization: Bearer <token>} header to the Inventory Service.
 * The Inventory Service can then trust these headers (since they come from the
 * gateway-validated request) without re-validating the full JWT.
 *
 * <p>This is the "Client Credentials: Order → Inventory verified" checkpoint:
 * the JWT that the client originally sent to the gateway is propagated intact
 * to downstream service calls, proving the order service acts on behalf of the
 * authenticated principal.
 */
@Component
public class FeignJwtInterceptor implements RequestInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(FeignJwtInterceptor.class);

    /** Gateway-injected header containing the authenticated user's ID (JWT sub claim). */
    public static final String X_USER_ID = "X-User-Id";

    /** Gateway-injected header containing the user's roles (comma-separated). */
    public static final String X_USER_ROLE = "X-User-Role";

    @Override
    public void apply(RequestTemplate template) {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        if (attributes == null) {
            logger.warn("[FEIGN-JWT] No request context found — JWT and user headers cannot be propagated.");
            return;
        }

        HttpServletRequest request = attributes.getRequest();

        // ── 1. Propagate the original JWT Bearer token ───────────────────────
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            logger.debug("[FEIGN-JWT] Propagating Authorization header (length={}).", authHeader.length());
            template.header(HttpHeaders.AUTHORIZATION, authHeader);
        } else {
            logger.warn("[FEIGN-JWT] Authorization header is missing or not a Bearer token.");
        }

        // ── 2. Propagate gateway-injected X-User-Id header ───────────────────
        String userId = request.getHeader(X_USER_ID);
        if (userId != null && !userId.isBlank()) {
            logger.debug("[FEIGN-JWT] Propagating X-User-Id={}.", userId);
            template.header(X_USER_ID, userId);
        }

        // ── 3. Propagate gateway-injected X-User-Role header ─────────────────
        String userRole = request.getHeader(X_USER_ROLE);
        if (userRole != null && !userRole.isBlank()) {
            logger.debug("[FEIGN-JWT] Propagating X-User-Role={}.", userRole);
            template.header(X_USER_ROLE, userRole);
        }
    }
}
