package com.apigw.proxy.ratelimit;

/**
 * 一次限流判定结果。
 *
 * @param allowed        是否放行
 * @param blockedScope   被卡在哪一层：{@code APP}=应用总量超了，{@code IP}=来源地址超了；
 *                       放行时为 {@code OK}
 * @param retryAfterSec  拒绝时告诉调用方的等待秒数（口径见 {@code rate-limit.lua}）：
 *                       当前固定窗口结束、下一窗重新开始计数还要等的整秒数（向上取整，>=1）。
 *                       放行时为 0。
 */
public record RateLimitVerdict(boolean allowed, String blockedScope, long retryAfterSec) {

    public static RateLimitVerdict pass() {
        return new RateLimitVerdict(true, "OK", 0);
    }

    public static RateLimitVerdict reject(String scope, long retryAfterSec) {
        return new RateLimitVerdict(false, scope, retryAfterSec);
    }
}
