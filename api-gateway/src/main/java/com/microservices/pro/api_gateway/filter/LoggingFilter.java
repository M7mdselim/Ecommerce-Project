package com.microservices.pro.api_gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * LoggingFilter is a GlobalFilter that logs all incoming requests and outgoing responses.
 * 
 * Why it exists:
 * To provide full visibility and traceability into request routing and status codes
 * across the gateway.
 * 
 * Order:
 * It executes with HIGHEST_PRECEDENCE so that every request is logged immediately upon entry,
 * before any authentication or routing filters are invoked.
 */
@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(LoggingFilter.class);

    /**
     * Intercepts the request to log details like HTTP method and path, then logs
     * the response HTTP status code once downstream processing completes.
     * 
     * Why it is needed:
     * To capture the boundary entrance and exit details of every incoming request.
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();
        
        logger.info("Gateway Incoming Request: Method={} Path={}", method, path);

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            var statusCode = exchange.getResponse().getStatusCode();
            logger.info("Gateway Outgoing Response: Path={} Status={}", path, statusCode);
        }));
    }

    /**
     * Sets the precedence of this filter to highest.
     * 
     * Why it is needed:
     * To ensure this filter executes first, establishing the start of the logging/tracing chain.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}