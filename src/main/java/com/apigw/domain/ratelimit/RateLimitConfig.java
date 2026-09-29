package com.apigw.domain.ratelimit;

import com.apigw.common.exception.BizException;

/**
 * 接入应用限流配置。
 *
 * - {@code appLimit}：该应用所有来源地址合计的每分钟额度；
 * - {@code sourceLimit}：该应用下单个来源地址的每分钟额度。
 *
 * null 表示这一层没有单独配置，由默认配置决定；-1 表示显式不限；正整数表示每分钟最多请求数。
 * 两层相互独立，可以只配一层，也可以同时配置。
 */
public record RateLimitConfig(Integer appLimit, Integer sourceLimit) {

    public static final int UNLIMITED = -1;

    public RateLimitConfig {
        appLimit = normalize(appLimit);
        sourceLimit = normalize(sourceLimit);
    }

    public static RateLimitConfig of(Integer appLimit, Integer sourceLimit) {
        return new RateLimitConfig(appLimit, sourceLimit);
    }

    public static RateLimitConfig unlimited() {
        return new RateLimitConfig(UNLIMITED, UNLIMITED);
    }

    private static Integer normalize(Integer limit) {
        if (limit == null) {
            return null;
        }
        if (limit == UNLIMITED) {
            return UNLIMITED;
        }
        if (limit < 1) {
            throw new BizException("限流额度必须为正整数，-1 表示不限流");
        }
        return limit;
    }
}
