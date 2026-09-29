package com.apigw.domain.ratelimit;

/**
 * 一次限流判定结果。
 *
 * @param allowed          是否放行
 * @param scope            被哪一层挡住：APP / SOURCE；放行时为 null
 * @param retryAfterMillis 被拒后到下一个固定分钟窗口可重试的等待毫秒数
 * @param windowStartMs    当前固定窗口起点（Unix 毫秒）
 * @param windowEndMs      当前固定窗口终点（Unix 毫秒）
 * @param appUsed          当前窗口应用总量计数
 * @param sourceUsed       当前窗口来源地址计数；未配置来源层时为 null
 */
public record RateLimitDecision(boolean allowed,
                                String scope,
                                long retryAfterMillis,
                                long windowStartMs,
                                long windowEndMs,
                                Long appUsed,
                                Long sourceUsed) {

    public static final String SCOPE_APP = "APP";
    public static final String SCOPE_SOURCE = "SOURCE";

    public static RateLimitDecision allow(long windowStartMs, long windowEndMs,
                                          Long appUsed, Long sourceUsed) {
        return new RateLimitDecision(true, null, 0, windowStartMs, windowEndMs,
                appUsed, sourceUsed);
    }

    public static RateLimitDecision denied(String scope, long windowStartMs, long windowEndMs,
                                           long retryAfterMillis, Long appUsed, Long sourceUsed) {
        return new RateLimitDecision(false, scope, retryAfterMillis, windowStartMs, windowEndMs,
                appUsed, sourceUsed);
    }
}
