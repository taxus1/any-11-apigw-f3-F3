package com.apigw.infrastructure.ratelimit;

import com.apigw.domain.ratelimit.RateLimitConfig;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 限流配置的 Redis 持久化端口。配置不改数据库表，统一存放在 Redis。
 */
public interface RateLimitConfigStore {

    String DEFAULT_APP_NO = "__default__";

    /** 读取所有应用的自定义配置，field=appNo，默认配置使用 {@link #DEFAULT_APP_NO}。 */
    Mono<Map<String, RateLimitConfig>> loadAll();

    Mono<RateLimitConfig> find(String appNo);

    /** 保存配置并原子发布变更通知，其它网关实例收到通知后立即刷新内存快照。 */
    Mono<Void> saveAndPublish(String appNo, RateLimitConfig config);

    /** 删除应用自定义配置并原子发布变更通知；默认配置也允许删除（删除后即不限流）。 */
    Mono<Void> deleteAndPublish(String appNo);
}
