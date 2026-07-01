package com.microservices.pro.api_gateway.configs;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * RateLimitConfig configures key resolvers used by the request rate limiter.
 * 
 * Why it exists:
 * Rate limiting requires a key to identify clients. We can resolve keys based on
 * the client's IP address (for unauthenticated/general requests) or by user ID
 * (for authenticated, user-specific traffic).
 */
@Configuration
public class RateLimitConfig {

    /**
     * Resolves the client's IP address as the rate limiting key.
     * Declared as Primary so that it is the default resolver unless specified otherwise.
     * 
     * Why it is needed:
     * To protect the gateway from brute-force attacks and DDOS by limiting requests
     * from individual IP addresses.
     */
    @Bean
    @Primary
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.justOrEmpty(
                exchange.getRequest().getRemoteAddress()
        ).map(addr -> addr.getAddress().getHostAddress());
    }

    /**
     * Resolves the user's ID (X-User-Id header) as the rate limiting key.
     * Falls back to "anonymous" if the header is not present.
     * 
     * Why it is needed:
     * To enforce rate limits per authenticated user account, preventing single users
     * from consuming excessive gateway throughput.
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> Mono.justOrEmpty(
                exchange.getRequest().getHeaders().getFirst("X-User-Id")
        ).defaultIfEmpty("anonymous");
    }
}
