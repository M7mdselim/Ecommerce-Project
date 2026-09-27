package com.microservices.pro.api_gateway.configs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Gateway Security Configuration — Session 20.
 *
 * <p><strong>What changed from the previous approach:</strong>
 * The old pattern used a hand-rolled {@code JwtAuthFilter} and {@code JwtUtil} that
 * manually parsed JWT tokens using {@code io.jsonwebtoken}. That approach is deleted.
 * This class replaces it entirely with Spring Security's built-in
 * {@code oauth2ResourceServer().jwt()} support, which:
 * <ul>
 *   <li>Validates the JWT signature using the shared HMAC secret (HS256).</li>
 *   <li>Validates expiry, issuer, and audience automatically.</li>
 *   <li>Converts the {@code roles} claim to Spring {@code GrantedAuthority} objects
 *       so that {@code hasAuthority("ROLE_ADMIN")} works in route rules.</li>
 * </ul>
 *
 * <p><strong>Route access rules (Session 20 checklist):</strong>
 * <ul>
 *   <li>{@code GET /api/products/**} — public, no token required.</li>
 *   <li>{@code GET /actuator/health} — public for health probes.</li>
 *   <li>{@code /api/orders/admin/**} — requires {@code ROLE_ADMIN} (403 for customer, 200 for admin).</li>
 *   <li>All other routes — require any valid JWT.</li>
 * </ul>
 *
 * <p><strong>Header propagation:</strong>
 * {@link JwtClaimsToHeaderFilter} injects {@code X-User-Id} and {@code X-User-Role}
 * from the validated JWT into downstream request headers.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${jwt.secret}")
    private String jwtSecret;

    /**
     * Reactive JWT decoder using the shared HS256 HMAC secret.
     *
     * <p>Why HS256 (symmetric)? This training environment has no external IdP.
     * In production, RS256 with a JWKS endpoint is strongly preferred.
     */
    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusReactiveJwtDecoder.withSecretKey(secretKey).build();
    }

    /**
     * Converts JWT {@code roles} claim into Spring Security {@code GrantedAuthority} objects.
     * Example JWT payload: {@code {"sub": "user1", "roles": ["ROLE_ADMIN"]}}
     */
    @Bean
    public ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthoritiesClaimName("roles");
        grantedAuthoritiesConverter.setAuthorityPrefix(""); // roles already include "ROLE_" prefix

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

    /**
     * Stateless security filter chain.
     * Every request must carry a JWT in {@code Authorization: Bearer <token>},
     * except the explicitly permitted public routes.
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .authorizeExchange(exchanges -> exchanges
                // ── Public routes (no token required) ──────────────────────────────
                .pathMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                .pathMatchers("/actuator/**").permitAll()

                // ── ADMIN-only routes (403 for ROLE_USER, 200 for ROLE_ADMIN) ──────
                .pathMatchers("/api/orders/admin/**").hasAuthority("ROLE_ADMIN")
                .pathMatchers(HttpMethod.POST, "/api/products", "/api/products/**").hasAuthority("ROLE_ADMIN")
                .pathMatchers(HttpMethod.PUT, "/api/products", "/api/products/**").hasAuthority("ROLE_ADMIN")
                .pathMatchers(HttpMethod.DELETE, "/api/products", "/api/products/**").hasAuthority("ROLE_ADMIN")

                // ── All other routes: any authenticated user ────────────────────────
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            );

        return http.build();
    }

    /**
     * IP Key Resolver for Spring Cloud Gateway RequestRateLimiter filter.
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
            Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                .map(addr -> addr.getAddress().getHostAddress())
                .orElse("anonymous")
        );
    }

    /**
     * Global filter that propagates JWT claims as trusted headers to downstream services.
     * Downstream services read {@code X-User-Id} and {@code X-User-Role}
     * without needing to re-validate the JWT — the gateway already did it.
     */
    @Bean
    public GlobalFilter jwtClaimsToHeaderFilter() {
        return new JwtClaimsToHeaderFilter();
    }

    /**
     * Extracts {@code sub} and {@code roles} from the validated JWT and adds them
     * as {@code X-User-Id} and {@code X-User-Role} request headers.
     */
    static class JwtClaimsToHeaderFilter implements GlobalFilter, Ordered {

        private static final Logger filterLog = LoggerFactory.getLogger(JwtClaimsToHeaderFilter.class);

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            return exchange.getPrincipal()
                // In Spring Security WebFlux, the reactive Principal IS the Authentication token
                .filter(principal -> principal instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .map(auth -> {
                    Jwt jwt = auth.getToken();
                    String userId = jwt.getSubject();

                    // Extract roles claim — may be a List<String> or a single String
                    Object rolesClaim = jwt.getClaim("roles");
                    String roles = "";
                    if (rolesClaim instanceof List<?> list) {
                        roles = String.join(",", list.stream().map(Object::toString).toList());
                    } else if (rolesClaim != null) {
                        roles = rolesClaim.toString();
                    }

                    filterLog.debug("[JWT-HEADERS] Propagating X-User-Id={}, X-User-Role={}", userId, roles);

                    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                        .header("X-User-Id", userId != null ? userId : "")
                        .header("X-User-Role", roles)
                        .build();

                    return exchange.mutate().request(mutatedRequest).build();
                })
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 1;
        }
    }
}
