package com.apigw.proxy.ratelimit;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FailOpenCircuitBreakerTest {

    @Test
    void openCircuitUsesFallbackWithoutWaitingForAction() {
        FailOpenCircuitBreaker breaker = new FailOpenCircuitBreaker(Duration.ofMinutes(1));
        AtomicInteger actionCalls = new AtomicInteger();

        String first = breaker.execute(() -> Mono.just("fallback"),
                () -> {
                    actionCalls.incrementAndGet();
                    return Mono.error(new RuntimeException("redis down"));
                }).block();
        String second = breaker.execute(() -> Mono.just("fallback"),
                () -> {
                    actionCalls.incrementAndGet();
                    return Mono.just("action");
                }).block();

        assertEquals("fallback", first);
        assertEquals("fallback", second);
        assertEquals(1, actionCalls.get(), "熔断打开后不应继续把请求卡在 Redis 调用上");
    }
}
