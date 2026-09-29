package com.apigw.proxy.ratelimit;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Redis 故障时的快速熔断。
 *
 * 这是保护网关自身的兜底，而不是配额算法的一部分：
 * - CLOSED：正常尝试 Redis；
 * - OPEN：短时间内不再等待 Redis，请求按限流故障的既定策略直接放行；
 * - HALF_OPEN：只放一笔探测，成功回到 CLOSED，失败重新 OPEN。
 *
 * 这样 Redis 超时时每个请求最多等一个很短的超时；Redis 已经确认不可用后，
 * 大量请求不会继续排队等待连接或超时，避免把网关线程/连接拖垮。
 */
public class FailOpenCircuitBreaker {

    private static final int CLOSED = 0;
    private static final int OPEN = 1;
    private static final int HALF_OPEN = 2;

    private final AtomicInteger state = new AtomicInteger(CLOSED);
    private final AtomicLong openedAtNanos = new AtomicLong(0);
    private final Duration openInterval;

    public FailOpenCircuitBreaker(Duration openInterval) {
        this.openInterval = openInterval;
    }

    public <T> Mono<T> execute(Supplier<Mono<T>> fallback, Supplier<Mono<T>> action) {
        if (state.get() == OPEN && !canTryAfterOpen()) {
            return fallback.get();
        }

        if (state.get() == OPEN && !state.compareAndSet(OPEN, HALF_OPEN)) {
            return fallback.get();
        }

        return action.get()
                .doOnSuccess(ignored -> state.set(CLOSED))
                .onErrorResume(err -> {
                    trip();
                    return fallback.get();
                });
    }

    private boolean canTryAfterOpen() {
        long openedAt = openedAtNanos.get();
        return System.nanoTime() - openedAt >= openInterval.toNanos();
    }

    private void trip() {
        openedAtNanos.set(System.nanoTime());
        state.set(OPEN);
    }
}
