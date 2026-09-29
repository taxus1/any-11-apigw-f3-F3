package com.apigw.proxy.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 限流功能参数。
 *
 * @param enabled             是否启用接入应用限流
 * @param refreshInterval     多实例配置兜底刷新周期
 * @param redisTimeout        单次 Redis 判定的最长等待时间；超时按 fail-open 放行
 * @param breakerOpenInterval Redis 连续故障后的熔断打开时长
 */
@ConfigurationProperties(prefix = "apigw.rate-limit")
public record RateLimitProperties(boolean enabled,
                                  Duration refreshInterval,
                                  Duration redisTimeout,
                                  Duration breakerOpenInterval) {

    public RateLimitProperties {
        if (refreshInterval == null) {
            refreshInterval = Duration.ofSeconds(10);
        }
        if (redisTimeout == null) {
            redisTimeout = Duration.ofMillis(100);
        }
        if (breakerOpenInterval == null) {
            breakerOpenInterval = Duration.ofSeconds(5);
        }
    }

    public long refreshIntervalMs() {
        return refreshInterval.toMillis();
    }
}
