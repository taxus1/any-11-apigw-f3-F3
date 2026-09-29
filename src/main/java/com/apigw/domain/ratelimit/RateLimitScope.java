package com.apigw.domain.ratelimit;

/**
 * 限流额度的作用范围。两层额度彼此独立，可同时配、只配一层或都不配：
 *
 * - {@link #APP}     应用总量：某应用一分钟内所有来源、所有网关实例合计的调用上限；
 * - {@link #IP}      来源地址：某应用下「单个来源地址」一分钟内的调用上限，
 *                    刷凶的只卡它自己，不连累同应用的其他正常来源；
 * - {@link #DEFAULT} 全局默认：没给某个应用单独配额度时按它走；默认也没配则不限。
 */
public enum RateLimitScope {

    APP,
    IP,
    DEFAULT;

    public static RateLimitScope from(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("scope 不能为空");
        }
        return switch (raw.trim().toUpperCase()) {
            case "APP" -> APP;
            case "IP" -> IP;
            case "DEFAULT" -> DEFAULT;
            default -> throw new IllegalArgumentException("scope 只能是 APP / IP / DEFAULT");
        };
    }
}
