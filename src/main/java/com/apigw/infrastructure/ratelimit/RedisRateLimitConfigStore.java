package com.apigw.infrastructure.ratelimit;

import com.apigw.domain.ratelimit.RateLimitConfig;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 限流配置的 Redis 实现。
 *
 * 存储形状：
 * <pre>
 * HASH apigw:ratelimit:{config}
 *   field = appNo，默认配置 field = __default__
 *   value = {"appLimit":1000,"sourceLimit":100}
 * </pre>
 *
 * key/channel 都带同一个 hash tag {@code {config}}，保存时用一段 Lua 同时完成 HSET 与
 * PUBLISH；配置写入和变更通知不会半路分叉。计数 key 不在这里，按应用分散到不同 slot。
 */
public class RedisRateLimitConfigStore implements RateLimitConfigStore {

    public static final String CONFIG_KEY = "apigw:ratelimit:{config}";
    public static final String EVENT_CHANNEL = "apigw:ratelimit:{config}:events";

    private static final RedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>(
            "redis.call('hset', KEYS[1], ARGV[1], ARGV[2]); "
                    + "redis.call('publish', KEYS[2], ARGV[3]); return 1",
            Long.class);

    private static final RedisScript<Long> DELETE_SCRIPT = new DefaultRedisScript<>(
            "redis.call('hdel', KEYS[1], ARGV[1]); "
                    + "redis.call('publish', KEYS[2], ARGV[2]); return 1",
            Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisRateLimitConfigStore(ReactiveStringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Map<String, RateLimitConfig>> loadAll() {
        return redis.<String, String>opsForHash().entries(CONFIG_KEY)
                .collectMap(Map.Entry::getKey, e -> deserialize(e.getValue()))
                .map(map -> (Map<String, RateLimitConfig>) new LinkedHashMap<>(map))
                .defaultIfEmpty(Map.of());
    }

    @Override
    public Mono<RateLimitConfig> find(String appNo) {
        return redis.<String, String>opsForHash().get(CONFIG_KEY, appNo)
                .map(this::deserialize);
    }

    @Override
    public Mono<Void> saveAndPublish(String appNo, RateLimitConfig config) {
        return redis.execute(SAVE_SCRIPT, List.of(CONFIG_KEY, EVENT_CHANNEL),
                        List.of(appNo, serialize(config), "changed:" + appNo))
                .next()
                .then();
    }

    @Override
    public Mono<Void> deleteAndPublish(String appNo) {
        return redis.execute(DELETE_SCRIPT, List.of(CONFIG_KEY, EVENT_CHANNEL),
                        List.of(appNo, "changed:" + appNo))
                .next()
                .then();
    }

    private String serialize(RateLimitConfig config) {
        try {
            return objectMapper.writeValueAsString(new ConfigDto(config.appLimit(), config.sourceLimit()));
        } catch (Exception e) {
            throw new IllegalStateException("限流配置序列化失败", e);
        }
    }

    private RateLimitConfig deserialize(String json) {
        try {
            ConfigDto dto = objectMapper.readValue(json, ConfigDto.class);
            return RateLimitConfig.of(dto.appLimit, dto.sourceLimit);
        } catch (Exception e) {
            throw new IllegalStateException("限流配置反序列化失败：" + json, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConfigDto {
        public Integer appLimit;
        public Integer sourceLimit;

        public ConfigDto() {
        }

        public ConfigDto(Integer appLimit, Integer sourceLimit) {
            this.appLimit = appLimit;
            this.sourceLimit = sourceLimit;
        }
    }
}
