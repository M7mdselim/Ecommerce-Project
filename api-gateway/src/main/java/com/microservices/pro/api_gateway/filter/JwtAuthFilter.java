package com.microservices.pro.api_gateway.filter;

import com.microservices.pro.api_gateway.security.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JwtAuthFilter acts as a security boundary by validating incoming JWT tokens.
 * 
 * Why it exists:
 * In our 4-tier microservices architecture, the Gateway is the single point of entry
 * and the security trust boundary. This filter intercepts requests, verifies JWT token
 * signatures and expiration, and strips incoming user identification headers to prevent
 * spoofing, replacing them with trusted, verified user credentials.
 * 
 * Security Boundary Rule:
 * The Gateway validates JWT only once. Downstream services must NEVER validate JWT again.
 * Instead, they trust the "X-User-Id" and "X-User-Role" headers.
 */
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private final JwtUtil jwtUtil;

    // Constructor Injection for the JWT utility class.
    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    // Path parser used to match incoming URI paths to patterns.
    private static final PathPatternParser PARSER = new PathPatternParser();

    // Nested record representing a route pattern that is exempt from JWT validation.
    private record PublicRoute(HttpMethod method, PathPattern pattern) {}

    // A list of routes that bypass authentication checking.
    private static final List<PublicRoute> PUBLIC_ROUTES = List.of(
        new PublicRoute(HttpMethod.GET, PARSER.parse("/api/products/**")),
        new PublicRoute(HttpMethod.GET, PARSER.parse("/actuator/health")),
        new PublicRoute(HttpMethod.POST, PARSER.parse("/actuator/info")),
        new PublicRoute(HttpMethod.POST, PARSER.parse("/api/auth/login")),
        new PublicRoute(HttpMethod.POST, PARSER.parse("/api/auth/register"))
    );

    /**
     * The filter method performs token extraction, validation, and request header mutation.
     * 
     * Why it is needed:
     * To enforce authentication policies globally on all non-public incoming requests.
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Step 1: Read HttpMethod and path pattern.
        HttpMethod method = exchange.getRequest().getMethod();
        String path = exchange.getRequest().getURI().getPath();

        // Check if path matches one of the public endpoints. If so, forward request immediately.
        if (isPublicRoute(method, path)) {
            return chain.filter(exchange);
        }

        // Step 2: Read Authorization header.
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorizedResponse(exchange, "Missing token");
        }

        // Step 3: Extract JWT.
        String token = authHeader.substring(7);

        try {
            // Validate token and parse claims (throws JwtException if expired/invalid).
            Claims claims = jwtUtil.validateToken(token);

            // Step 4: Extract subject (User ID) and role.
            String userId = claims.getSubject();
            String role = claims.get("role", String.class);

            // If role is missing, reject request.
            if (role == null || role.trim().isEmpty()) {
                return unauthorizedResponse(exchange, "Invalid or expired token");
            }

            // Step 5 & 6: Header Enrichment (Establish trust boundary).
            // We remove existing user headers to prevent client-side spoofing, then inject verified values.
            ServerHttpRequest enrichedRequest = exchange.getRequest().mutate()
                    .headers(httpHeaders -> {
                        httpHeaders.remove("X-User-Id");
                        httpHeaders.remove("X-User-Role");
                        httpHeaders.add("X-User-Id", userId);
                        httpHeaders.add("X-User-Role", role);
                      })
                    .build();

            // Step 7: Forward the mutated request downstream.
            return chain.filter(exchange.mutate().request(enrichedRequest).build());

        } catch (JwtException e) {
            // Step 8: Catch authentication/JWT exceptions and respond with 401 Unauthorized.
            return unauthorizedResponse(exchange, "Invalid or expired token");
        }
    }

    /**
     * Helper method to determine if a request path matches the public routes list.
     * 
     * Why it is needed:
     * Allows unauthenticated paths like login, register, or public product listings to pass through.
     */
    private boolean isPublicRoute(HttpMethod method, String path) {
        PathContainer pathContainer = PathContainer.parsePath(path);
        return PUBLIC_ROUTES.stream().anyMatch(route -> 
            (route.method() == null || route.method().equals(method)) && route.pattern().matches(pathContainer)
        );
    }

    /**
     * Helper method to write a 401 Unauthorized reactive JSON response.
     * 
     * Why it is needed:
     * Prevents request from forwarding to backend services and provides clean JSON details on authentication failure.
     */
    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format(
                "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"%s\"}",
                message
        );
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * Defines execution order for the filter.
     * 
     * Why it is needed:
     * Must execute after LoggingFilter (highest precedence) but before any route mapping filters.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}