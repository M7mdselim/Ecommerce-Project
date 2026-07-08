package com.microservice.pro.order_service.config;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.github.resilience4j.core.ContextAwareScheduledThreadPoolExecutor;
import io.github.resilience4j.spring6.fallback.FallbackExecutor;
import io.github.resilience4j.spring6.spelresolver.SpelResolver;

import io.github.resilience4j.spring6.bulkhead.configure.BulkheadAspect;
import io.github.resilience4j.spring6.bulkhead.configure.BulkheadAspectExt;
import io.github.resilience4j.spring6.bulkhead.configure.BulkheadConfigurationProperties;

import io.github.resilience4j.spring6.circuitbreaker.configure.CircuitBreakerAspect;
import io.github.resilience4j.spring6.circuitbreaker.configure.CircuitBreakerAspectExt;
import io.github.resilience4j.spring6.circuitbreaker.configure.CircuitBreakerConfigurationProperties;

import io.github.resilience4j.spring6.retry.configure.RetryAspect;
import io.github.resilience4j.spring6.retry.configure.RetryAspectExt;
import io.github.resilience4j.spring6.retry.configure.RetryConfigurationProperties;

import io.github.resilience4j.spring6.timelimiter.configure.TimeLimiterAspect;
import io.github.resilience4j.spring6.timelimiter.configure.TimeLimiterAspectExt;
import io.github.resilience4j.spring6.timelimiter.configure.TimeLimiterConfigurationProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

import java.util.List;

@Configuration
public class ResilienceAspectConfig {

    @Bean
    public BulkheadAspect bulkheadAspect(BulkheadConfigurationProperties bulkheadProperties,
                                         @Nullable ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry,
                                         BulkheadRegistry bulkheadRegistry,
                                         List<BulkheadAspectExt> bulkheadAspectExtList,
                                         FallbackExecutor fallbackExecutor,
                                         SpelResolver spelResolver) {
        return new BulkheadAspect(bulkheadProperties, threadPoolBulkheadRegistry, bulkheadRegistry, bulkheadAspectExtList, fallbackExecutor, spelResolver) {
            @Override
            public int getOrder() {
                return 1; // Outermost aspect
            }
        };
    }

    @Bean
    public TimeLimiterAspect timeLimiterAspect(TimeLimiterRegistry timeLimiterRegistry,
                                               TimeLimiterConfigurationProperties timeLimiterProperties,
                                               List<TimeLimiterAspectExt> timeLimiterAspectExtList,
                                               FallbackExecutor fallbackExecutor,
                                               SpelResolver spelResolver,
                                               @Nullable ContextAwareScheduledThreadPoolExecutor contextAwareScheduledThreadPoolExecutor) {
        return new TimeLimiterAspect(timeLimiterRegistry, timeLimiterProperties, timeLimiterAspectExtList, fallbackExecutor, spelResolver, contextAwareScheduledThreadPoolExecutor) {
            @Override
            public int getOrder() {
                return 2;
            }
        };
    }

    @Bean
    public CircuitBreakerAspect circuitBreakerAspect(CircuitBreakerConfigurationProperties circuitBreakerProperties,
                                                     CircuitBreakerRegistry circuitBreakerRegistry,
                                                     List<CircuitBreakerAspectExt> circuitBreakerAspectExtList,
                                                     FallbackExecutor fallbackExecutor,
                                                     SpelResolver spelResolver) {
        return new CircuitBreakerAspect(circuitBreakerProperties, circuitBreakerRegistry, circuitBreakerAspectExtList, fallbackExecutor, spelResolver) {
            @Override
            public int getOrder() {
                return 3;
            }
        };
    }

    @Bean
    public RetryAspect retryAspect(RetryConfigurationProperties retryProperties,
                                   RetryRegistry retryRegistry,
                                   List<RetryAspectExt> retryAspectExtList,
                                   FallbackExecutor fallbackExecutor,
                                   SpelResolver spelResolver,
                                   @Nullable ContextAwareScheduledThreadPoolExecutor contextAwareScheduledThreadPoolExecutor) {
        return new RetryAspect(retryProperties, retryRegistry, retryAspectExtList, fallbackExecutor, spelResolver, contextAwareScheduledThreadPoolExecutor) {
            @Override
            public int getOrder() {
                return 4; // Innermost aspect
            }
        };
    }
}
