package com.apigw.interfaces.rest.ratelimit;

import com.apigw.domain.ratelimit.RateLimitConfig;

/**
 * 限流额度配置请求。字段省略或为 null 表示这一层不覆盖；-1 表示显式不限；正整数表示每分钟额度。
 */
public record RateLimitConfigRequest(Integer appLimit, Integer sourceLimit) {

    public RateLimitConfig toDomain() {
        return RateLimitConfig.of(appLimit, sourceLimit);
    }
}
