package com.apigw.proxy.ratelimit;

import com.apigw.domain.ratelimit.RateLimitConfig;
import com.apigw.domain.ratelimit.RateLimitDecision;
import com.apigw.support.EnabledIfRedis;
import com.apigw.support.RedisAvailableCondition;
import io.lettuce.core.resource.ClientResources;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RedisRateLimiter 的真机测试。重点验证两层额度、来源隔离、固定窗口不带余数、
 * 以及多请求并发时 Lua 原子计数不会超发。
 */
@EnabledIfRedis
class RedisRateLimiterTest {

    private ReactiveRedisConnectionFactory factory;
    private ReactiveStringRedisTemplate redis;
    private RedisRateLimiter limiter;

    @BeforeEach
    void setUp() {
        var clientConfig = LettuceClientConfiguration.builder()
                .clientResources(ClientResources.create())
                .build();
        var connConfig = new org.springframework.data.redis.connection.RedisStandaloneConfiguration(
                RedisAvailableCondition.host(), RedisAvailableCondition.port());
        var lettuceFactory = new LettuceConnectionFactory(connConfig, clientConfig);
        lettuceFactory.afterPropertiesSet();
        this.factory = lettuceFactory;
        this.redis = new ReactiveStringRedisTemplate(lettuceFactory);
        RateLimitProperties properties = new RateLimitProperties(
                true, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(5));
        this.limiter = new RedisRateLimiter(redis, properties);
        redis.delete(RedisRateLimiter.counterKey("app-test")).block();
    }

    @AfterEach
    void tearDown() {
        redis.delete(RedisRateLimiter.counterKey("app-test")).block();
        ((LettuceConnectionFactory) factory).destroy();
    }

    @Test
    void appQuotaIsSharedAndReturnsWaitUntilNextMinuteWindow() {
        RateLimitDecision first = acquire("1.1.1.1", 2, -1).block();
        RateLimitDecision second = acquire("1.1.1.2", 2, -1).block();
        RateLimitDecision third = acquire("1.1.1.3", 2, -1).block();

        assertTrue(first.allowed());
        assertTrue(second.allowed());
        assertFalse(third.allowed());
        assertEquals(RateLimitDecision.SCOPE_APP, third.scope());
        assertTrue(third.retryAfterMillis() > 0);
        assertTrue(third.retryAfterMillis() <= Duration.ofMinutes(1).toMillis());
    }

    @Test
    void sourceQuotaBlocksOnlyBadSourceAndDoesNotAffectOtherSources() {
        RateLimitConfig config = RateLimitConfig.of(100, 1);

        RateLimitDecision first = limiter.checkAndAcquire("app-test", "2.2.2.1", config).block();
        RateLimitDecision repeated = limiter.checkAndAcquire("app-test", "2.2.2.1", config).block();
        RateLimitDecision other = limiter.checkAndAcquire("app-test", "2.2.2.2", config).block();

        assertTrue(first.allowed());
        assertFalse(repeated.allowed());
        assertEquals(RateLimitDecision.SCOPE_SOURCE, repeated.scope());
        assertTrue(other.allowed(), "只该挡住刷爆的来源，不能连累同应用其它来源");
    }

    @Test
    void expiredWindowStartsFromZeroAndDoesNotCarryRemainder() {
        String key = RedisRateLimiter.counterKey("app-test");
        redis.opsForHash().put(key, "w", "1")
                .then(redis.opsForHash().put(key, "a", "999"))
                .then(redis.opsForHash().put(key, "i:" + "3.3.3.3", "999"))
                .block();

        RateLimitDecision decision = acquire("3.3.3.3", 100, 100).block();

        assertTrue(decision.allowed());
        assertEquals(1L, decision.appUsed(), "新固定窗口必须从 0 重新计数，不能带上一窗余数");
        assertEquals(1L, decision.sourceUsed());
    }

    @Test
    void concurrentAcquisitionsCannotOverspendAppQuota() {
        AtomicInteger allowed = new AtomicInteger();
        Flux<RateLimitDecision> requests = Flux.range(1, 300)
                .flatMap(i -> acquire("4.4.4." + (i % 20 + 1), 100, -1)
                        .doOnNext(decision -> {
                            if (decision.allowed()) {
                                allowed.incrementAndGet();
                            }
                        }), 64);

        long rejected = requests.filter(decision -> !decision.allowed()).count().block();

        assertEquals(100, allowed.get(), "全局共享额度不能因并发而超发");
        assertEquals(200L, rejected);
    }

    private Mono<RateLimitDecision> acquire(String ip, int appLimit, int sourceLimit) {
        return limiter.checkAndAcquire("app-test", ip, RateLimitConfig.of(appLimit, sourceLimit));
    }
}
