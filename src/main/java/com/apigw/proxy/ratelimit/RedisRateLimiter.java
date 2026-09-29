package com.apigw.proxy.ratelimit;

import com.apigw.domain.ratelimit.RateLimitConfig;
import com.apigw.domain.ratelimit.RateLimitDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * 基于 Redis + Lua 的全局固定窗口限流器。
 *
 * 窗口是所有网关实例共享的自然分钟：{@code floor(unix_ms / 60000)}。
 * 到点后新窗口从空计数开始，旧窗口计数不会折算或带入新窗口。
 *
 * 每个应用一份 Redis Hash：
 * <pre>
 * apigw:ratelimit:{<sha256(appNo)>}:window
 *   field  w     当前窗口起点
 *   field  a     当前窗口应用总计数
 *   field  i:<sha256(canonicalIp)>  当前窗口某来源地址计数
 * </pre>
 *
 * Hash tag 只按应用编号生成，应用总量键与该应用的所有来源字段落在同一 Redis Cluster slot，
 * Lua 可一次原子判定两层额度；不同应用仍分散在不同 slot。
 *
 * 计数和判定放在同一段 Lua 内由 Redis 单线程原子执行：并发请求不会出现两个实例都先读到
 * 未超额、再各自放行的超发，也不会重复计数导致少放。
 *
 * 旧窗口 Hash 带 120s TTL；活跃窗口每次计数都续期。自然分钟切换后最多保留约两个窗口，
 * 存储随活跃应用/来源有界回收，不会无限膨胀。
 */
@Slf4j
public class RedisRateLimiter {

    private static final Duration KEY_TTL = Duration.ofSeconds(120);
    private static final int SOURCE_LIMITED = 2;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = new DefaultRedisScript<>(
            "local key = KEYS[1] "
                    + "local appLimit = tonumber(ARGV[1]) "
                    + "local sourceLimit = tonumber(ARGV[2]) "
                    + "local ipField = ARGV[3] "
                    + "local ttl = tonumber(ARGV[4]) "
                    + "local time = redis.call('time') "
                    + "local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000) "
                    + "local window = math.floor(now / 60000) * 60000 "
                    + "local reset = window + 60000 "
                    + "local storedWindow = tonumber(redis.call('hget', key, 'w')) "
                    + "if storedWindow ~= window then "
                    + "  redis.call('del', key) "
                    + "  redis.call('hset', key, 'w', window) "
                    + "end "
                    + "local appUsed = tonumber(redis.call('hget', key, 'a')) "
                    + "if appUsed == nil then appUsed = 0 end "
                    + "if appLimit >= 0 and appUsed >= appLimit then "
                    + "  redis.call('pexpire', key, ttl) "
                    + "  return {0, 1, appUsed, 0, window, reset, now} "
                    + "end "
                    + "local sourceUsed = 0 "
                    + "if sourceLimit >= 0 and ipField ~= '' then "
                    + "  sourceUsed = tonumber(redis.call('hget', key, ipField)) "
                    + "  if sourceUsed == nil then sourceUsed = 0 end "
                    + "  if sourceUsed >= sourceLimit then "
                    + "    redis.call('pexpire', key, ttl) "
                    + "    return {0, 2, appUsed, sourceUsed, window, reset, now} "
                    + "  end "
                    + "end "
                    + "appUsed = redis.call('hincrby', key, 'a', 1) "
                    + "if sourceLimit >= 0 and ipField ~= '' then "
                    + "  sourceUsed = redis.call('hincrby', key, ipField, 1) "
                    + "else sourceUsed = -1 end "
                    + "redis.call('pexpire', key, ttl) "
                    + "return {1, 0, appUsed, sourceUsed, window, reset, now}",
            List.class);

    private final ReactiveStringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final FailOpenCircuitBreaker breaker;

    public RedisRateLimiter(ReactiveStringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
        this.breaker = new FailOpenCircuitBreaker(properties.breakerOpenInterval());
    }

    /**
     * 检查并占用一个请求名额。
     *
     * @param appNo    已通过应用鉴权的可信应用编号
     * @param sourceIp 已规范化的来源地址；拿不到时传 null，且配置了来源层则该层无法计数
     */
    public Mono<RateLimitDecision> checkAndAcquire(String appNo, String sourceIp, RateLimitConfig config) {
        Integer appLimit = config.appLimit();
        Integer sourceLimit = config.sourceLimit();
        boolean appUnlimited = appLimit == null || appLimit == RateLimitConfig.UNLIMITED;
        boolean sourceUnlimited = sourceLimit == null || sourceLimit == RateLimitConfig.UNLIMITED;
        if (appUnlimited && sourceUnlimited) {
            return Mono.just(RateLimitDecision.allow(0, 0, null, null));
        }

        String key = counterKey(appNo);
        String ipField = sourceUnlimited || sourceIp == null ? "" : "i:" + sha256(sourceIp);
        List<String> args = List.of(
                String.valueOf(appUnlimited ? RateLimitConfig.UNLIMITED : appLimit),
                String.valueOf(sourceUnlimited ? RateLimitConfig.UNLIMITED : sourceLimit),
                ipField,
                String.valueOf(KEY_TTL.toMillis()));

        Mono<RateLimitDecision> action = redis.execute(SCRIPT, List.of(key), args)
                .next()
                .map(result -> toDecision((List<?>) result))
                .timeout(properties.redisTimeout())
                .single();

        // Redis 超时/连不上/命令失败：fail-open。每个请求最多等待 redisTimeout；
        // 熔断打开后连这一小段等待也跳过，优先保证网关不被依赖故障拖垮。
        return breaker.execute(() -> {
            log.warn("限流计数暂时不可用，本次请求按 fail-open 放行 appNo={}", appNo);
            return Mono.just(RateLimitDecision.allow(0, 0, null, null));
        }, () -> action);
    }

    private static RateLimitDecision toDecision(List<?> values) {
        boolean allowed = asLong(values, 0) == 1;
        long rejectedScope = asLong(values, 1);
        long appUsed = asLong(values, 2);
        long sourceUsedRaw = asLong(values, 3);
        long windowStart = asLong(values, 4);
        long windowEnd = asLong(values, 5);
        long now = asLong(values, 6);
        Long sourceUsed = sourceUsedRaw < 0 ? null : sourceUsedRaw;

        if (allowed) {
            return RateLimitDecision.allow(windowStart, windowEnd, appUsed, sourceUsed);
        }
        long retryMillis = Math.max(0, windowEnd - now);
        String scope = rejectedScope == SOURCE_LIMITED
                ? RateLimitDecision.SCOPE_SOURCE : RateLimitDecision.SCOPE_APP;
        return RateLimitDecision.denied(scope, windowStart, windowEnd,
                retryMillis, appUsed, sourceUsed);
    }

    private static long asLong(List<?> values, int index) {
        Object value = values.get(index);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    static String counterKey(String appNo) {
        return "apigw:ratelimit:{" + sha256(appNo) + "}:window";
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
