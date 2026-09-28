package com.microservices.pro.api_gateway.configs;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Programmatic RouteLocator configuration for API Gateway.
 * Guarantees routes are registered regardless of YAML property prefix differences.
 */
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
            .route("product-service", r -> r
                .path("/api/products", "/api/products/**")
                .filters(f -> f
                    .rewritePath("/api/products(?<segment>.*)", "/api/v1/products${segment}")
                    .addResponseHeader("X-Platform", "microservices-pro")
                )
                .uri("lb://PRODUCT-SERVICE")
            )
            .route("order-service", r -> r
                .path("/api/orders", "/api/orders/**")
                .uri("lb://ORDER-SERVICE")
            )
            .route("order-service-admin", r -> r
                .path("/api/orders/admin/**")
                .uri("lb://ORDER-SERVICE")
            )
            .route("inventory-service", r -> r
                .path("/api/v1/inventory", "/api/v1/inventory/**")
                .uri("lb://INVENTORY-SERVICE")
            )
            .build();
    }
}
